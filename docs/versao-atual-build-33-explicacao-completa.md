# Versão atual do BYD Motor Sound — explicação completa da build 33

Última conferência: **28 de agosto de 2026**, no fuso de São Paulo.

Este arquivo explica, em linguagem simples, o que foi feito, o que realmente está dentro da
versão atual do aplicativo, o que existe apenas como material de preparação no computador e o que
ainda falta terminar. Ele descreve o estado real da **build 33**, e não somente o plano original.

Se você quiser uma leitura por etapas, comece por “qual é o estado real hoje?”, leia o dicionário,
depois as seções sobre arquitetura Android, conta-giros/câmbio e caminho do áudio. As seções de provas,
hashes e apêndices existem para permitir uma conferência completa; não é necessário memorizá-las para
entender o funcionamento normal do app.

## Leia isto primeiro: qual é o estado real hoje?

A base técnica do aplicativo foi quase toda reconstruída. O app agora tem o catálogo visual dos
carros, câmbio de apresentação, serviço que continua funcionando em segundo plano, importação
segura de pacotes, decodificação FLAC nativa, mixer de áudio próprio, favoritos, imagens, controles
de efeitos e ferramentas de diagnóstico.

Entretanto, o conjunto final de sons de todos os carros **ainda não foi concluído**. Esta diferença
é muito importante:

| Parte | Estado real |
| --- | --- |
| Lista dos carros oficiais | Pronta: 178 carros utilizáveis aparecem no seletor. |
| Código Android que recebe e toca pacotes | Implementado na build 33. |
| Pacotes finais dos 153 sons diferentes | Ainda não existem como um conjunto final aprovado. |
| Pacotes intermediários no computador | Existem 41 arquivos `.aclib`, mas são testes e resultados parciais misturados. |
| Pacotes importados no emulador atual | Nenhum. |
| Som do carro no emulador atual | Silêncio, porque não há pacote de áudio importado. O `AudioTrack` está ativo, mas não tem amostras para tocar. |
| Teste em um BYD Seal real | Ainda pendente para esta arquitetura e estes pacotes. |
| Produto final pronto para uso diário | Não. A versão é uma base de desenvolvimento testável. |

O APK que está aberto no emulador é:

- arquivo: `mobile/build/outputs/apk/debug/engine-sounds-simulator-build-33-debug.apk`;
- versão: `1.0.33`;
- tamanho: `14.849.875` bytes;
- SHA-256: `EC857989F48B6F62BCF970BD1619471AD349DDD79AE485B75C6398BD082FA786`;
- criado em: `2026-08-28 10:21:11 -03:00`;
- instalado no `emulator-5554` em `2026-08-28 10:21:35 -03:00`;
- o app estava aberto e em primeiro plano no momento desta conferência.

O número `e3da6a4` mostrado dentro do app é o último commit do Git. A árvore de trabalho tem muitas
alterações ainda não commitadas. Por isso, o identificador mais confiável desta build específica é
o SHA-256 completo do APK acima, e não apenas `e3da6a4`.

Esse campo lê somente o ponteiro `.git/HEAD`: ele não acrescenta uma marca “dirty” nem calcula o
conteúdo dos arquivos. Em alguns formatos de referência empacotada pode até resultar em `unknown`.
Além disso, a numeração automática aumenta quando o nome da tarefa Gradle contém `assemble`; apenas
montar de novo pode consumir o próximo número, mesmo sem uma mudança funcional.

## Um mapa simples do sistema

O trabalho ficou dividido em três peças. Elas se ajudam, mas não são a mesma aplicação.

```text
Instalação local do Assetto Corsa
        |
        | leitura; nunca altera o jogo
        v
Laboratório e compilador no computador
  - entende bancos FMOD e dados de cada carro
  - mede o comportamento real do jogo sem abrir os alto-falantes
  - separa somente os sons permitidos
  - cria catálogo, imagens e pacotes .aclib privados
        |
        | importação manual pelo usuário
        v
Aplicativo Android no BYD/emulador
  - lê pedal, freio, velocidade e posição P/N/D
  - simula o movimento do Seal
  - calcula o conta-giros e as trocas do carro escolhido
  - decodifica somente o FLAC do carro selecionado
  - mistura os sons e envia estéreo para o sistema do carro
```

Os diretórios principais são:

- app Android: `D:\Users\sgabr\AndroidStudioProjects\BYDMotorSound`;
- laboratório e compilador do Assetto Corsa:
  `C:\Users\Gabriel\Documents\ChatGPT\assettocorsa`;
- instalação do jogo:
  `D:\Program Files (x86)\Steam\steamapps\common\assettocorsa`;
- mídia e provas privadas geradas:
  `D:\Users\sgabr\BYDMotorSoundData`.

## Pequeno dicionário antes dos detalhes

Alguns termos aparecem muitas vezes. Aqui está o significado deles sem pressupor conhecimento de
áudio ou programação.

- **RPM:** rotações por minuto. É a velocidade de giro apresentada pelo conta-giros.
- **Amostra de áudio:** uma gravação curta, como um motor sustentando determinada rotação ou um
  estalo do escapamento.
- **Loop:** trecho de uma gravação que volta ao início e se repete. Se o ponto de emenda estiver
  errado, escuta-se um clique a cada volta.
- **Camada:** uma gravação que toca junto com outras. Por exemplo, marcha lenta, escapamento,
  admissão, turbo e transmissão podem ser camadas diferentes.
- **One-shot:** som que toca uma vez, como uma troca de marcha, um estouro ou uma válvula de alívio.
- **Varispeed ou pitch:** mudança da velocidade de reprodução da amostra para acompanhar o RPM. Ao
  acelerar a reprodução, o som fica mais agudo; ao desacelerar, fica mais grave.
- **Curva:** uma tabela de pontos que diz quanto volume ou quanto pitch usar para cada RPM, pedal,
  pressão do turbo ou outro valor.
- **PCM16:** o som já descompactado, representado por números de 16 bits. É o formato que o mixer
  usa para fazer contas rápidas.
- **48 kHz:** são 48 mil amostras por segundo, por canal.
- **Estéreo:** dois canais: esquerdo e direito.
- **FLAC:** compressão sem perda. Ele diminui o tamanho no armazenamento, mas, quando aberto,
  devolve exatamente os mesmos números PCM16 originais.
- **FMOD:** sistema de áudio usado pelo Assetto Corsa. Um banco FMOD guarda gravações, curvas,
  sorteios, filtros, regras e ligações entre os sons.
- **Banco `.bank`:** arquivo do jogo produzido pelo FMOD. Não é apenas um pacote de WAVs.
- **Mixer:** parte que combina todas as camadas e efeitos em um único sinal estéreo.
- **`AudioTrack`:** saída de áudio de baixo nível do Android usada para entregar o PCM ao sistema.
- **Decodificar:** transformar FLAC em PCM16 utilizável pelo mixer.
- **Família de som:** um conjunto de áudio compartilhado por um ou mais carros. Dois carros que têm
  bancos exatamente iguais não precisam guardar duas cópias.
- **Catálogo:** lista com os carros e seus dados, sem necessariamente conter o áudio.
- **Manifesto:** arquivo JSON dentro do pacote que descreve exatamente cada áudio, curva, hash,
  loop, efeito, carro e regra.
- **`.aclib`:** pacote privado criado para este app. É um ZIP determinístico com manifesto, FLACs e
  imagens.
- **Hash SHA-256:** uma impressão digital do arquivo. Se um único byte mudar, o hash muda. Ele é
  usado para detectar arquivo errado ou corrompido.
- **Oracle ou referência:** teste que pergunta ao FMOD original “o que você realmente faria?” e
  guarda a resposta como prova.
- **Código nativo:** código C/C++ compilado para o processador do aparelho. Aqui ele decodifica FLAC
  e mistura PCM com menos trabalho para a máquina virtual do Android.
- **JNI:** ponte entre o código Kotlin do app e o código nativo C++.
- **Serviço em primeiro plano:** componente Android que pode continuar trabalhando enquanto outra
  tela está aberta. “Em primeiro plano” aqui significa que ele mantém uma notificação visível; não
  significa que a tela do app precisa ficar aberta.
- **Underrun:** momento em que a saída de áudio pede dados e o app não conseguiu preparar dados a
  tempo. Isso pode causar falha, corte ou estalo.
- **Clipping:** áudio alto demais, que bate no limite numérico e distorce.
- **Histerese:** uma margem diferente para ligar e desligar uma condição. A histerese de 150 RPM da
  redução de marcha foi removida. Existe outra histerese, separada, somente no indicador do
  limitador; ela não altera o ponto de redução.

## Decisões pedidas por você que foram incorporadas

Estas são as decisões que guiaram a versão atual:

1. Somente carros originais da Kunos e DLCs oficiais entram no catálogo.
2. O app não inclui pneus, rodas, freios, chassi, dano, derrapagem, controle de tração, buzina,
   vento, porta ou carroceria.
3. A camada chamada `LOAD`, que contém o caminho de motor sob carga, é proibida no pacote Android.
4. A marcha lenta (`IDLE`) é obrigatória e deve seguir a curva original do carro.
5. Continuam permitidos `COAST`, textura, admissão, escapamento, turbo, spool, válvula de alívio,
   transmissão, limitador, trocas, overrun, pops, bangs, cracks e outros transientes do motor.
6. O arquivo guardado é FLAC nível 8. O conteúdo final é PCM16, 48 kHz, estéreo.
7. Foram removidos os modos AUTO de canais, quad, 5.1, 7.1, espelhamento e troca de canais. A saída
   é sempre estéreo.
8. O movimento físico do carro continua sendo o modelo do BYD Seal Performance. O carro escolhido
   no catálogo só muda a apresentação do conta-giros, do câmbio e do som.
9. O processamento importante continua quando o app vai para segundo plano. O trabalho apenas
   visual para quando a tela fica escondida.
10. Fechar a tela com Home não para o som. Remover o app dos Recentes ou usar **Stop** na notificação
    encerra o serviço.
11. O app não segura `wake lock`, não inicia no boot e respeita perda/duck de foco de áudio.
12. O seletor suporta busca, favoritos persistentes, estado instalado/não instalado e imagens.
13. Existe mute geral, mixer por faixa, isolamento de efeitos e audição dedicada de pops/bangs.
14. A redução automática usa o RPM calculado no qual a marcha seguinte caiu, sem a antiga margem
    de 150 RPM.

## O laboratório de Assetto Corsa no computador

Antes de mudar o Android, foi criado um laboratório local separado. Ele serve para observar o jogo
e descobrir as regras reais, sem adivinhar pelo nome dos WAVs.

Esse laboratório:

- encontra a instalação Steam do Assetto Corsa;
- abre `common.bank`, `common.strings.bank` e o banco do carro selecionado usando o mesmo FMOD
  Studio 1.08.12 fornecido pelo jogo;
- lê os arquivos de física do carro, inclusive `data.acd`, razões de marcha, corte, turbo, câmeras,
  massa, pneus e curvas;
- executa uma simulação local com acelerador e freio;
- reproduz eventos nativos de motor interno, motor externo, turbo, limitador, backfire,
  transmissão e troca de marcha;
- não cria eventos de pneu, roda, freio, chassi, dano e outros sons fora do escopo;
- permite escolher carro e mostra a imagem de preview instalada;
- permite favoritar carros; a estrela fica visível no menu e os favoritos sobem para o começo;
- tem um botão para silenciar motor/transmissão e ouvir os outros efeitos;
- tem um botão para disparar o evento original de pops e bangs isoladamente;
- estabilizou a parte superior do layout para o seletor e o estado da transmissão não deslocarem os
  elementos ao mudar de texto.

### As três posições de escuta do laboratório

O laboratório de computador tem três posições:

- **Cockpit:** usa o evento persistente `engine_int` e os efeitos internos.
- **Bonnet:** usa `engine_ext` com o ouvinte na coordenada `BONNET_CAMERA_POS` do próprio carro.
- **Exhaust:** usa a mesma instância `engine_ext`, mas move o ouvinte para 0,82 metro atrás do ponto
  do motor traseiro. O Assetto Corsa não possui um terceiro evento exclusivo de escapamento; a
  diferença vem do posicionamento, cone e filtros espaciais do FMOD.

Ao trocar entre cockpit e exterior, o laboratório deixa o evento anterior sair com fade e liga o
outro, como o jogo. Ao trocar bonnet por exhaust, ele apenas move o ouvinte; o loop externo não
reinicia.

O som externo abafado da Toyota Supra MKIV foi investigado nesse nível. O ponto principal era não
tratar o som externo como uma versão filtrada de um mix genérico. O laboratório passou a usar o
evento exterior nativo, sua orientação e a posição correta do ouvinte. Isso deixou o caminho de
referência mais próximo do jogo. Essa solução de câmera **não está no Android atual**: o app Android
foi fixado em estéreo com perspectiva de cabine, conforme o plano final.

### Câmbio automático no laboratório e no Android não são idênticos

O laboratório recuperou o `AUTO_SHIFTER` do executável do Assetto Corsa. Ele roda a física a
333 1/3 Hz e preserva autoclutch, autoblip, corte de gás, passagem temporária por neutro, timers e
ordem `Autoclutch -> AutoBlip -> AutoShifter -> GearChanger -> Drivetrain`.

O Android não leva o solucionador físico inteiro do Assetto Corsa. Ele usa um câmbio de
**apresentação** separado sobre o movimento elétrico do Seal. O Android importa as razões, RPMs e
duração das trocas, mas sua finalidade é controlar ponteiro, pitch e eventos sonoros. Portanto, é
correto dizer que o laboratório reproduz muito mais da máquina de estados original do jogo; o
Android reproduz a apresentação necessária sem fingir que o Seal ganhou a física de combustão do
carro escolhido.

### LOAD no laboratório e LOAD no aplicativo

O laboratório nativo pode tocar o evento completo do jogo, inclusive as partes de carga, porque ele
é uma referência para comparação. O compilador de pacotes Android, por outro lado, remove
instrumentos `LOAD` antes da captura. Isso não é contradição: uma ferramenta observa o original; a
outra produz o recorte que você escolheu para o app.

## Auditoria dos carros instalados

Na conferência de 28 de agosto de 2026, a pasta do jogo tinha exatamente **180 diretórios de carros**.
Todos os 180 pertenciam à lista oficial aceita. Não havia diretório de carro modificado restante e
nenhum mod é aceito pelo compilador.

A decisão de “oficial” não confia no campo autor do `ui_car.json`, que um mod poderia falsificar. Ela
usa uma lista fechada da versão 1.16.4 e DLCs conhecidos. A pasta de registro
`removed-ac-empty-folders` está vazia nesta conferência: não havia outro carro modificado restante
para apagar ou mover. O compilador atual não altera a instalação do jogo.

Dois desses diretórios oficiais são placeholders sem banco de áudio utilizável:

- `ks_ferrari_488_challenge_evo`;
- `ks_ferrari_488_gt3_2020`.

Eles não recebem um pacote falso. Por isso, o catálogo útil tem **178 carros**.

Além da limpeza atual da instalação, o compilador possui uma segunda proteção: ele compara cada ID
com uma lista oficial fechada. Se um mod voltar a ser instalado no futuro, ele continua fora do
catálogo mesmo que tenha uma pasta e um banco válidos.

## Por que 178 carros viram 153 famílias de som?

Algumas variações de carro usam bancos de áudio exatamente iguais. O programa calcula o SHA-256 do
banco. Se o conteúdo for idêntico, um único pacote guarda o áudio e cada carro mantém seus próprios
dados de motor, câmbio e imagem.

Há 14 famílias compartilhadas por 39 carros:

| Início do hash da família | Carros que compartilham o áudio |
| --- | --- |
| `87d6643f` | `abarth500`, `abarth500_s1` |
| `112f7214` | `bmw_1m`, `bmw_1m_s3` |
| `1c5de873` | `bmw_m3_e30`, `bmw_m3_e30_s1` |
| `0c83ac95` | `bmw_m3_e92`, `bmw_m3_e92_drift`, `bmw_m3_e92_s1` |
| `321ebea8` | `bmw_z4`, `bmw_z4_drift`, `bmw_z4_s1` |
| `a293307d` | `ferrari_458`, `ferrari_458_s3` |
| `a1600d25` | `ferrari_f40`, `ferrari_f40_s3` |
| `5eae8307` | `ks_audi_sport_quattro`, `ks_audi_sport_quattro_s1` |
| `a4b806d8` | `ks_lamborghini_countach`, `ks_lamborghini_countach_s1` |
| `26f3a0de` | `ks_ruf_rt12r`, `ks_ruf_rt12r_awd` |
| `31dc3cc1` | `lotus_2_eleven`, `lotus_2_eleven_gt4` |
| `4eb18480` | seis Elise/Exige: `lotus_elise_sc`, S1, S2, `lotus_exige_240`, S3 e Scura |
| `d17b8077` | `lotus_evora_gtc`, `lotus_evora_gx` |
| `0a35cfe4` | sete Evora/Exige: Evora GTE, GTE Carbon, S, S2; Exige S, Roadster e V6 Cup |

A maior família compartilhada tem sete carros. As outras 139 famílias atendem um carro cada.

## Como o compilador entende os bancos do jogo

Um banco FMOD não é interpretado por nome de arquivo somente. O compilador usa três tipos de
evidência:

1. **Dados estáticos:** lê a estrutura do banco, os instrumentos, curvas, regiões, grupos, efeitos e
   ligações entre parâmetros.
2. **FMOD original em modo silencioso:** carrega uma cópia temporária do banco no FMOD 1.08.12, sem
   dispositivo de som, e observa o que realmente é agendado ou renderizado.
3. **Provas com uma fonte isolada:** na cópia temporária, altera apenas a chance de disparo das
   outras fontes para zero. Antes de alterar, confere hash, posição e bytes; depois confere que a
   instalação original não mudou.

O parser está preso a versões conhecidas:

- FModBankParser no commit `b7cfcfc60c93fcca7e4ffe666144e7ee3ed7ce77`;
- Fmod5Sharp no commit `b69f9afd44f36d8d290b529768b5ff061a8497fe`;
- adaptações locais para .NET 8 e o formato antigo usado pelo AC 1.08.

O resultado da leitura estrutural dos 153 bancos foi:

- 153 de 153 famílias lidas com sucesso;
- bancos na versão de arquivo `0x50`;
- zero trecho desconhecido do banco;
- zero falha de mapeamento entre evento e amostra;
- zero GUID de parâmetro sem solução;
- 2.525 eventos;
- 9.865 instrumentos, sendo 9.450 fontes de onda e 415 instrumentos compostos;
- 8.059 recursos de áudio incorporados;
- 13.680 controladores e 13.680 curvas;
- 3.910 parâmetros;
- 642 regiões de transição;
- 503 moduladores ADSR e 218 moduladores aleatórios;
- 4.894 nós de efeitos e 9.636 barramentos.

O relatório estrutural atual é
`.aclib-local/bank-graph-audit-v3/summary.json`, SHA-256
`B45DEB37B8567E40A441BC0C915175D81ED79653922D0D41354B093EC3016007`.

Esses números provam que a estrutura foi localizada. Eles não significam, sozinhos, que todas as
regras já foram transformadas em pacotes finais.

## Como LOAD é removido sem depender do nome do WAV

O classificador examina o caminho completo de cada fonte até a raiz do evento e observa as curvas
de pedal, o local da fonte, o tipo de evento e se a fonte é loop ou disparo único. Ele não usa o
nome da gravação como decisão principal.

Uma fonte de motor é considerada `LOAD` quando sua lógica sobe com o pedal, não inclui pedal zero
ou fica pelo menos 12 dB mais fraca ao soltar o pedal. Se os dados não forem suficientes, ela não é
aceita por adivinhação: fica ambígua e é excluída.

Classificação atual das 9.450 fontes:

| Classe | Quantidade | Tratamento |
| --- | ---: | --- |
| `LOAD` | 2.969 | Excluídas. São 2.652 loops e 317 one-shots. |
| Motor com curva decrescente | 1.641 | Candidato permitido. |
| Motor ainda audível ao soltar | 103 | Candidato permitido. |
| Motor sem dependência do pedal | 113 | Candidato permitido. |
| Transiente de motor | 11 | Candidato permitido, ainda exige prova de ciclo de vida. |
| Troca de marcha | 685 | Candidato permitido. |
| Gear grind | 98 | Inventariado, mas não faz parte da reprodução normal pedida. |
| Limitador | 73 | Candidato permitido. |
| Overrun/backfire | 1.363 | Candidato permitido. |
| Transmissão | 106 | Candidato permitido. |
| Turbo contínuo | 66 | Candidato permitido, ainda exige fechamento de ciclo de vida. |
| Turbo transiente | 171 | Candidato permitido. |
| Fora do núcleo pedido | 2.049 | Excluídas. |
| Ambíguas | 2 | Excluídas por segurança. |

As duas fontes ambíguas pertencem à Ferrari 488 GT3. O FMOD dispara cada uma ao iniciar dentro de
uma região de pedal alto, mas não dispara novamente ao cruzar a região subindo ou descendo. Como não
foi possível provar se são carga ou um transiente permitido, elas continuam fora do pacote.

O arquivo completo de classificação é
`.aclib-local/source-role-classification-v2.json`, SHA-256
`72E8C6D7453F74153111D26309914EA4BCD6427E1A8FC1E1CB7FFA16A28CB548`.

Todas as 153 famílias têm pelo menos uma fonte contínua permitida que permanece audível com o pedal
solto. Ainda existem 4.153 fontes permitidas com mais de uma função exata possível, por exemplo
`COAST` versus `EXHAUST` versus textura. A exclusão de LOAD está mais avançada do que a identificação
perfeita do nome funcional de cada camada. Isso é uma das razões pelas quais o conjunto final ainda
não foi publicado.

## O formato de áudio escolhido

O processo final planejado para cada fonte é:

1. o FMOD original renderiza em silêncio para PCM16, 48 kHz, estéreo;
2. os pontos de loop são conferidos e, quando permitido pela prova, reparados;
3. o volume é calibrado para não clipar;
4. o PCM é codificado em FLAC nível 8;
5. o FLAC é decodificado novamente;
6. quadro, quantidade de amostras e SHA-256 do PCM precisam ser idênticos ao material anterior ao
   FLAC.

FLAC é usado somente para guardar e transportar. Durante a condução, o app não fica descompactando
o mesmo bloco repetidamente. Ele abre todo o FLAC do carro escolhido em uma thread de fundo e guarda
PCM16 pronto na memória nativa. Isso usa mais memória que o arquivo comprimido, mas poupa trabalho
no caminho de áudio e torna os loops previsíveis.

Os primeiros números usados para planejar memória foram:

| Carro de referência | FLAC no armazenamento | PCM16 aberto na memória |
| --- | ---: | ---: |
| Huracán Trofeo EVO2 | aproximadamente 4,00 MiB | aproximadamente 6,55 MiB |
| Aventador SV | aproximadamente 7,55 MiB | aproximadamente 17,02 MiB |

Esses valores são referências de dimensionamento, não o tamanho garantido dos futuros pacotes
finais.

## Correção dos estalos do Huracán

Converter de 44,1 kHz para 48 kHz não consertava o problema porque a taxa de amostragem não era a
única causa. As extrações antigas já tinham emendas ruins e, no caso de `c1`, 11 amostras batendo no
limite numérico. FLAC conservaria esses defeitos perfeitamente.

Foi criado um gerador de regressão que isola as fontes `Hur_C1`, `Hur_C3` e `Hur_LIM` em cópias
temporárias do banco, renderiza cada uma duas vezes e exige que os dois resultados sejam idênticos.
Depois ele escolhe e mede a emenda, dá margem de volume, codifica em FLAC e prova a volta exata ao
PCM.

| Fonte | Loop final, no formato `[início, fim exclusivo)` | Pico da emenda | Pico final | Amostras no limite |
| --- | ---: | ---: | ---: | ---: |
| `c1` | `[1458, 94541)` | −55,82 dBFS | −5,09 dBFS | 0 |
| `c3` | `[703, 119656)` | −50,48 dBFS | −6,62 dBFS | 0 |
| limitador | `[1525, 15174)` | −44,69 dBFS | −3,10 dBFS | 0 |

Três execuções completas produziram o mesmo relatório, SHA-256
`2641D9D87EC8847BCA84733C89805D6A67C3F3BACC1E1C56537FF5D9C74B145F`.

O **Lamborghini Huracán Super Trofeo EVO2** usado nesse teste é material privado de regressão e não
faz parte dos 178 carros oficiais encontrados. O `ks_lamborghini_huracan_st` é o Huracán oficial do
catálogo e é um carro diferente. A prova numérica do loop está pronta; a escuta prolongada no BYD
ainda não foi feita.

## Catálogo de trabalho e recursos detectados

O catálogo de trabalho atual tem 633.504 bytes e SHA-256
`8FBC33821FE0CFEFC0FA5E37B9C55982ACDC1837004D5CB24DAF2A40B53F0786`. O hash canônico do conteúdo,
isto é, a impressão digital usada internamente sem depender da forma de escrita do JSON, é
`EA0B3DC5432E9D2CF1EAB57F78E67BFB90A6BB1F8D6672B86784716DCBCB9B65`.

O inventário de eventos desse catálogo de trabalho encontrou:

| Recurso indicado | Famílias entre as 153 |
| --- | ---: |
| IDLE | 153 |
| COAST | 153 |
| TEXTURE | 153 |
| INTAKE | 153 |
| EXHAUST | 153 |
| TURBO | 77 |
| SPOOL | 77 |
| BOV | 76 |
| TRANSMISSION | 76 |
| LIMITER | 137 |
| SHIFT | 153 |
| OVERRUN | 134 |
| POPS/BANGS/CRACKS | 134 |

Esses números dizem que o grafo do banco contém caminhos candidatos. Eles ainda não certificam que
cada um já virou um FLAC final aprovado.

### Carros híbridos encontrados

Foram encontrados 12: Ferrari LaFerrari, Audi R18 e-tron quattro, Ferrari F138, FXX K, SF15-T,
SF70H, McLaren P1, P1 GTR, Porsche 918 Spyder, Porsche 919 Hybrid 2015 e 2016 e Toyota TS040.

Os dados híbridos são guardados como metadados e peculiaridades sonoras. Eles não alteram a física
de movimento do Seal.

### Carros com controladores especiais de turbo

Foram encontrados 11 carros com arquivos de controle de turbo dependentes de RPM, pedal ou marcha:

- Ferrari 488 GT3: 2 arquivos;
- Ferrari 488 GTB: 10;
- Ferrari SF15-T: 1;
- Ferrari SF70H: 1;
- Mazda RX7 Spirit R: 1;
- McLaren 650S GT3: 2;
- Porsche 919 Hybrid 2016: 1;
- Porsche 962 C Short Tail: 2;
- Porsche 911 Turbo S: 2;
- Toyota Supra MKIV: 1;
- McLaren MP4-12C GT3: 2.

O runtime Android possui um avaliador sem alocação para essas tabelas. Ele combina entradas de RPM,
pedal e marcha, filtros, limites e operações de soma ou multiplicação. A física do turbo acompanha
pressão, wastegate, RPM de referência, gamma, subida, descida, pressão normalizada e BOV.

### Outras peculiaridades já representadas

- 76 carros possuem conjuntos alternativos de relações de marcha. Eles são preservados como
  informação; o app não escolhe uma combinação alternativa sem uma regra original que diga qual
  usar.
- 20 carros são identificados como tração integral.
- a BMW M3 E30 Gr.A exige um DSP adicional de ganho;
- a Tatuus FA01 declara uma pista de BOV, mas ela não possui amostra audível. O app mantém o silêncio
  em vez de inventar uma válvula de alívio;
- 19 carros têm pontos de autoblip em uma ordem incomum. A ordem escrita pelo autor é preservada e
  não é reordenada numericamente;
- peculiaridades são associadas ao carro, não copiadas cegamente para todos os irmãos que usam o
  mesmo banco.

## O que existe hoje na pasta privada do compilador

No momento da pausa existem materiais intermediários, provas e tentativas antigas. Eles não formam
uma versão final única:

- `D:\Users\sgabr\BYDMotorSoundData\aclib\packs`: 41 arquivos `.aclib`, total de 85.591.617 bytes;
- árvore `aclib\families`: 1.457 arquivos e 313.230.603 bytes, incluindo 805 FLACs, 514 WAVs,
  86 JSONs e 52 JPGs;
- árvore `oracles`: 3.038 arquivos e 5.949.033.098 bytes, incluindo 2.509 JSONs, 388 WAVs,
  132 bancos temporários e 9 logs.

Há três arquivos de trabalho importantes, explicitamente não finais:

| Arquivo | Tamanho | SHA-256 |
| --- | ---: | --- |
| `capture-plan-v2-property-working.json` | 12.123.344 bytes | `2954F8048A9DA20121D96269AFEEE95E2CFAF0461E648F99FE569BBEFD2022DE` |
| `compile-all-omissions-property-working.json` | 38.267 bytes | `8E76B424075E1090F1D993EE4E26359D3EF8AB6800AA45DE2F859A887B6FD8D3` |
| `hybrid-property-working.json` | 7.850 bytes | `39D98B0E2A301200AB7977E6A591AF94E73A3AB2BED985E34CD981C2D4FF5A9A` |

Essas pastas são privadas, ficam fora do APK e são ignoradas pelo Git.

## Por que o Android não abre o `.bank` diretamente

Foi considerada a ideia de interpretar o banco dentro do carro. Ela não foi adotada por estes
motivos:

- o banco contém um grafo complexo, não somente áudio;
- o comportamento depende da versão antiga exata do FMOD;
- portar todas as regras e todos os DSPs para Android elevaria risco, memória, CPU e licenciamento;
- qualquer leitura ou alocação inesperada durante o áudio aumentaria o risco de crackle;
- uma compilação offline permite testar e provar cada fonte antes de levá-la ao BYD.

A solução escolhida faz o trabalho pesado no computador uma vez e entrega ao celular um manifesto
pequeno mais FLACs já preparados. O Android ainda executa curvas, gatilhos, turbo, prioridades e
mistura em tempo real, mas não precisa interpretar o formato proprietário inteiro do banco.

## Como é um pacote `.aclib`

O pacote é um ZIP sem compressão adicional dos membros, porque o áudio interno já está em FLAC. A
estrutura é:

```text
manifest.json
audio/<faixa>.flac
previews/<id-do-carro>.jpg, .png ou .webp
```

O manifesto registra, entre outros dados:

- família e carros membros;
- formato de áudio;
- caminho e SHA-256 de cada FLAC;
- SHA-256 do PCM16 depois de abrir o FLAC;
- quantidade exata de quadros;
- início e fim exclusivo do loop;
- RPM de referência para pitch;
- volume, pico e curvas de RPM/pedal;
- função da faixa;
- gatilhos e prioridade do canal FMOD;
- motor, marcha lenta, redline, limitador e limite do conta-giros;
- razões de marcha, relação final, tempos de troca e RPM de aterrissagem;
- turbo, híbrido, throttle map, autoblip, corte de gás e opções de câmbio;
- árvores de escolha normal, aleatória e sequencial dos one-shots;
- fontes que são comprovadamente silenciosas;
- hashes do banco, catálogo, plano de captura, ferramentas e provas.

A versão 2 do manifesto também consegue representar programas completos de eventos, limites de
vozes, comportamento quando uma fonte chega exatamente a volume zero e os tratamentos específicos
de limitador e turbo. A versão 1 continua aceita somente por compatibilidade e testes antigos.

## Segurança da importação

O usuário escolhe o catálogo e um ou mais pacotes pelo seletor de documentos do Android. Essa forma
é chamada Storage Access Framework, ou SAF. O app não precisa receber acesso geral ao armazenamento.

Antes de aceitar um pacote, o app:

- limita o arquivo total a 512 MiB;
- limita qualquer membro a 256 MiB;
- limita a expansão total a 768 MiB;
- limita a 512 membros;
- limita cada FLAC a 128 MiB;
- limita cada imagem a 16 MiB;
- limita manifesto e catálogo a 4 MiB;
- limita um bloco de metadados FLAC a 16 MiB;
- rejeita pastas, nomes absolutos, `..`, barras invertidas, dois-pontos, byte nulo e nomes acima de
  240 bytes;
- rejeita membros duplicados e membros que não estejam no manifesto;
- exige ZIP `STORED`, tamanho conhecido e tamanho armazenado coerente;
- confere assinatura/formato de imagem e FLAC;
- exige 48 kHz, dois canais e 16 bits no `STREAMINFO` do FLAC;
- confere SHA-256 do arquivo;
- abre o FLAC com o decodificador nativo e confere quantidade de quadros e SHA-256 do PCM;
- verifica IDs oficiais, membros da família e o hash do catálogo esperado;
- rejeita qualquer palavra isolada `LOAD`, sem diferença entre maiúsculas e minúsculas;
- rejeita campos JSON desconhecidos, ausentes ou duplicados;
- exige IDLE audível no RPM de marcha lenta;
- exige que a soma conservadora da mixagem padrão fique em −3 dBFS ou menos.

A instalação é transacional: primeiro vai para `incoming`, depois para uma pasta `.staging`. O pacote
antigo é renomeado como backup somente no momento de trocar. Se a nova troca falhar, o anterior é
restaurado. Na próxima inicialização, restos de operação interrompida são limpos ou recuperados.

Quando vários pacotes são selecionados, eles são importados um a um em uma thread própria. Cada um
é atômico. Se o pacote número 20 for inválido, os 19 anteriores continuam instalados; o seletor é
reconstruído uma vez no fim da seleção.

Os arquivos ficam em:

```text
files/assetto_sound_library_v1/incoming
files/assetto_sound_library_v1/installed
```

Desinstalar o app ou limpar seus dados remove esses pacotes. Backup Android foi desligado para não
copiar a mídia privada automaticamente.

## O catálogo dentro do Android

O APK traz uma lista mínima e imutável dos 178 carros. “Mínima” significa que ela contém os nomes,
IDs oficiais e a relação entre carro e família sonora, mas **não contém o áudio**. Isso tem duas
vantagens: a tela pode mostrar todos os carros desde a primeira abertura e o APK não redistribui a
mídia privada do Assetto Corsa.

O estado mostrado no seletor tem este significado:

- **instalado**: existe um pacote `.aclib` válido, já copiado para a área privada do aplicativo;
- **não instalado**: o nome está no catálogo, mas ainda não há áudio local para ele;
- **favorito**: o usuário marcou o carro com a estrela; isso não instala nem altera o áudio;
- **sem imagem**: o pacote não trouxe uma prévia válida, então aparece um cartão neutro.

Quando um catálogo completo for importado, ele acrescentará os dados detalhados de motor, câmbio,
efeitos e peculiaridades. O aplicativo nunca confia cegamente no texto importado: ele cruza o carro
com a lista oficial interna, confere a família sonora e recalcula o estado “instalado” olhando os
pacotes realmente presentes no armazenamento privado.

O carro inicial usado pela build 33 é `ks_lamborghini_huracan_st`. Se uma instalação antiga ainda
tiver um dos dois IDs históricos usados antes do catálogo, há uma migração para o ID oficial atual.

### Favoritos

A estrela solicitada foi implementada. O favorito é salvo pelo ID oficial do carro em preferências
privadas do aplicativo. No seletor:

1. favoritos aparecem primeiro;
2. dentro do grupo de favoritos e do grupo normal, os nomes ficam em ordem alfabética;
3. a busca encontra por nome, marca ou ID interno;
4. tocar novamente na estrela remove o favorito;
5. atualizar o catálogo ou trocar o pacote não apaga a preferência.

O catálogo gerado sempre começa com `favorite = false`, porque um favorito é uma escolha pessoal e
não deve fazer parte de um arquivo compartilhado entre instalações.

### Imagens dos carros

As 178 prévias oficiais foram localizadas no computador e o catálogo privado sabe qual imagem
pertence a cada carro. No conjunto de trabalho atual, elas ocupam 24.393.534 bytes.

As imagens também não entram no APK. Elas chegam pelo catálogo/pacote privado e são abertas em uma
thread de segundo plano. Para economizar memória, o app reduz a imagem para aproximadamente 512 px
no carro selecionado e 256 px nas linhas do seletor. Se a tela não está visível, esse trabalho de
imagem não é feito. Se não houver imagem, aparece `NO PREVIEW`, em vez de tentar adivinhar uma foto.

O arquivo antigo `apex_v10_car.png` ainda existe no projeto, mas não participa do caminho atual do
catálogo; ele é um resíduo visual legado.

## A grande mudança de arquitetura no Android

Antes, a tela principal era dona da simulação e do áudio. Isso é inadequado para um painel que deve
continuar funcionando quando outro aplicativo fica na frente: ao esconder a tela, o Android pode
parar a `Activity` e, com ela, parar o motor sonoro.

Na build 33, a responsabilidade ficou separada assim:

```text
Sensores BYD ou pedal de teste
            │
            ▼
DriveRuntimeService, que continua em segundo plano
    ├── leitura dos sinais do carro
    ├── física longitudinal do BYD Seal
    ├── câmbio de apresentação e conta-giros
    ├── gatilhos de troca, turbo, limitador e estouros
    ├── abertura do FLAC e mixer nativo
    ├── AudioTrack e foco de áudio
    └── diagnósticos do funcionamento
            │
            ▼ somente enquanto a tela está visível
MainActivity + Compose
    ├── ponteiros, números e animações
    ├── lista, busca, favoritos e imagens
    ├── medidores e textos de depuração
    └── botões enviados de volta ao serviço
```

O nome técnico do componente que continua vivo é `DriveRuntimeService`. Ele é um **serviço em
primeiro plano** do Android. “Em primeiro plano” aqui não quer dizer que sua tela fica na frente;
quer dizer que o sistema mostra uma notificação permanente para deixar explícito que existe um
trabalho de áudio ativo.

O serviço é o único dono de:

- `DriveController`, que coordena o ciclo de 200 Hz;
- leitor de telemetria BYD;
- modelo de movimento do Seal;
- conta-giros e câmbio fictício de apresentação;
- trabalhador que abre o FLAC;
- mixer nativo em C++;
- `AudioTrack`, que entrega os samples ao Android;
- foco de áudio;
- contadores e registros de diagnóstico.

A tela recebe apenas uma ligação local chamada `DriveRuntimeBinder`. Essa ligação oferece comandos
e fotografias instantâneas do estado, mas não transfere para a tela a posse do áudio ou das threads.
Por isso, girar a tela, ir para Home ou voltar ao aplicativo não recria o `AudioTrack`, não zera a
marcha, não recomeça os loops e não deveria causar uma interrupção audível.

### O que continua e o que para quando o aplicativo é escondido

Ao apertar Home ou abrir outro aplicativo, continuam funcionando:

- leitura de acelerador, freio, velocidade e posição do câmbio da BYD;
- simulação do movimento;
- cálculo de RPM e marchas;
- detecção de troca, limitador, turbo, BOV, overrun, pops, bangs e cracks;
- mistura, reprodução e diagnóstico essencial do áudio.

Param imediatamente:

- atualização do Compose;
- construção de objetos de tela e listas de faixas;
- medidores, rótulos, formatação de números e painel de depuração;
- animações;
- carregamento e redução de imagens;
- coleta periódica de fotografias do estado para a interface.

Ao voltar, a tela pede uma fotografia atual imediatamente e então volta a pedir no máximo uma a cada
17 ms, aproximadamente 59 vezes por segundo. Ela não tenta reconstruir os 200 passos por segundo que
ocorreram escondidos; apenas mostra o estado atual.

Se o acelerador ou o freio estava sendo segurado por toque ou teclado, perder o foco da janela ou
ocultar a `Activity` solta os dois. Isso impede que um pedal de teste fique preso em 100%. Os pedais
reais da BYD continuam válidos, porque pertencem ao serviço e não à tela.

### Início, restauração e parada

Ao começar uma sessão de direção, o aplicativo inicia o serviço imediatamente. O serviço usa
`START_STICKY`, mas a palavra “sticky” não significa que ele sempre voltará. Há uma preferência que
registra se a sessão estava realmente ativa:

- se o processo for removido apenas por pressão de memória, uma sessão ativa pode ser restaurada;
- se o usuário dispensar a tarefa na tela de aplicativos recentes, o serviço grava “parado pelo
  usuário” e não se restaura sozinho;
- o botão **Stop** da notificação faz a mesma parada completa;
- abrir novamente o aplicativo é uma nova ação explícita e pode limpar essa marca para iniciar uma
  nova sessão;
- não existe receptor de inicialização; ligar o carro ou reiniciar o Android não inicia o app sozinho.

Na parada completa, o volume desce suavemente, as entradas manuais são zeradas, as threads e o leitor
são fechados fora da thread principal, o foco de áudio é devolvido, a notificação é removida e o
serviço chama `stopSelf()`. O coordenador usa um número de geração para impedir que uma inicialização
antiga termine depois de uma parada nova e ressuscite recursos já encerrados.

Se o processo inteiro for morto abruptamente, o Android recupera seus recursos, mas não existe
garantia de que a rotina assíncrona tenha tempo de executar cada passo de fechamento antes da morte.
Essa é uma diferença normal entre uma parada solicitada e uma eliminação imediata pelo sistema.

O tempo pedido para a descida de volume é limitado entre 200 e 2.500 ms. Internamente, cinco
constantes de tempo cabem nesse intervalo para que o fim seja muito próximo de silêncio antes de
fechar a saída.

### Notificação

A notificação tem baixa importância, não toca som, não vibra e não cria selo no ícone. Ela mostra o
carro selecionado e se o som está ligado ou mudo. Os únicos comandos são:

- **Mute/Unmute**, para silenciar ou restaurar o áudio sem parar a sessão;
- **Stop**, para encerrar tudo.

Ela só é atualizada quando o carro ou o estado de som muda. Não há uma atualização de notificação a
cada RPM, pois isso despertaria CPU e bateria.

Em Android 13 ou posterior, se o usuário negar a permissão de notificação, o serviço em primeiro
plano ainda pode funcionar, mas os botões Mute e Stop podem não ficar visíveis na gaveta. O pedido é
feito pela Activity visível e somente uma vez fica registrado. Ainda é possível parar pela interface
ou removendo dos Recentes. Isso normalmente não afeta a versão mais antiga do Android usada pelo
DiLink alvo, mas deve ser testado no aparelho exato.

### Sem bloqueio de sono

O aplicativo não segura um `wake lock`. Um `wake lock` é uma ordem para impedir que o processador
durma. A decisão foi não forçar isso: com a tela apagada, ignição desligada ou suspensão profunda do
sistema BYD, o próprio Android ainda pode suspender o trabalho. Portanto, “funciona em segundo plano”
significa Home e troca de aplicativos; não é uma promessa de continuar durante todo tipo de sono
profundo do carro.

## Como a tela conversa com o serviço

O `DriveRuntimeBinder` aceita, na versão atual:

- obter uma fotografia única do estado e consultar se o serviço está pronto;
- informar se a interface está visível;
- definir pedal manual, modo de entrada e posição P/N/D;
- ligar/desligar o som e mudar volume/ajustes;
- escolher carro;
- favoritar ou desfavoritar;
- importar catálogo, um pacote ou vários pacotes;
- mudar ganhos, mute e solo de faixas;
- ativar/desativar categorias de efeitos;
- isolar efeitos e fazer a audição de pops e bangs;
- marcar o instante de um estalo;
- exportar diagnóstico;
- reiniciar o leitor BYD;
- iniciar o roteiro automático de validação;
- mandar parar a sessão.

O estado de alta frequência usa números e estruturas primitivas com um mecanismo de revisão. Em
termos simples: o ciclo escreve todos os números, marca que terminou, e a tela só aceita uma leitura
em que o começo e o fim pertençam à mesma revisão. Assim ela não vê metade do RPM novo com metade da
marcha antiga, e o ciclo de áudio não precisa criar objetos ou usar uma trava pesada.

## Entrada real da BYD e entrada simulada

O leitor BYD procura, por reflexão, as classes fornecidas pelo sistema DiLink. Reflexão aqui significa
que o app procura a classe pelo nome em tempo de execução, em vez de incluir uma cópia privada das
bibliotecas da montadora. Isso permite compilar e testar no emulador, onde essas classes não existem.

O aplicativo solicita somente estas permissões da BYD:

- leitura de velocidade comum;
- leitura de informações de velocidade;
- leitura da caixa de câmbio.

Ele não pede permissões de escrita `SET`, não inclui as classes BYD dentro do APK e não tem permissão
de Internet. Um contexto protetor entrega ao wrapper da montadora apenas essas três permissões de
leitura. Isso reduz o risco de uma biblioteca tentar escrever algo no veículo.

Um único trabalhador lê aproximadamente a cada 20 ms:

- profundidade do acelerador;
- profundidade do freio;
- velocidade;
- modo/código do câmbio automático.

Cada valor é validado antes de ser usado:

| Sinal | Faixa aceita |
|---|---:|
| Acelerador | 0 a 100% |
| Freio | 0 a 100% |
| Velocidade | 0 a 282 km/h |
| Código de marcha | 0 a 32 |

Há um histórico circular das últimas 128 leituras para verificar frequência, atraso e falhas sem
deixar a memória crescer indefinidamente.

Os modos de entrada são:

- **AUTO**: usa o veículo somente quando acelerador **e** freio são válidos; caso contrário usa o
  simulador;
- **VEHICLE**: exige o veículo; sinais ausentes viram zero por segurança;
- **SIM**: usa somente os pedais manuais ou comandos de teste.

A velocidade externa só é usada junto com pedais veiculares válidos. A posição física do câmbio só
trava a apresentação no modo `VEHICLE`; ré é tratada como neutro para a apresentação sonora, porque
esta versão não simula um conjunto separado de sons de ré.

O emulador não possui as classes DiLink. Portanto, a build 33 prova a ausência segura e o caminho de
simulação, mas a leitura real ainda precisa ser confirmada dentro do BYD Seal.

## Como é possível testar sem tocar no computador

Foi criado um receptor de comandos somente na variante de depuração. Ele aceita comandos ADB, que
são mensagens enviadas por cabo ou pelo emulador. Assim, testes podem mover os pedais e trocar opções
sem mouse, teclado ou foco de janela.

O receptor exige a permissão de sistema `android.permission.DUMP`. Um aplicativo comum não consegue
chamá-lo; o shell de depuração consegue. A versão de distribuição não exporta esse receptor.

O script [tools/adb-drive.ps1](../tools/adb-drive.ps1) simplifica os comandos. Ele consegue:

- ajustar acelerador e freio ou zerar ambos;
- mudar `AUTO`, `VEHICLE` e `SIM`;
- selecionar P, N ou D;
- ligar/desligar som;
- selecionar carro e favorito;
- importar catálogo, pacote individual ou lote;
- iniciar audição de efeito;
- marcar um crackle;
- exportar diagnóstico;
- pedir estabilização de memória;
- iniciar validação automática;
- obter uma fotografia do estado;
- parar o serviço.

A fila aceita no máximo 32 comandos pendentes e a importação em lote aceita até 153 pacotes. Esses
limites evitam que um teste errado encha a memória. Os testes automatizados foram executados sem
necessidade de abrir uma janela ou tocar som; quando você liberou o computador, o APK também foi
instalado e aberto visualmente no emulador.

Os roteiros automáticos escolhem explicitamente o emulador, recusam um aparelho físico e colocam o
volume de mídia do emulador em zero. A importação ADB aceita apenas arquivos `.aclib` minúsculos,
diretos e não recursivos numa pasta `adb-import`; o mesmo importador de produção valida o conteúdo.

## Duas simulações separadas: o carro elétrico e o motor que aparece no painel

Uma decisão importante do projeto original foi preservada: o automóvel que se move é o BYD Seal
Performance; o motor a combustão escolhido serve para som, RPM, marcha e apresentação.

Isso evita um erro conceitual. Selecionar um Ferrari não transforma massa, aerodinâmica e torque do
Seal nos de um Ferrari. O pedal e a velocidade real continuam pertencendo ao BYD. Por cima dessa
velocidade, o app pergunta: “se o som selecionado tivesse estas relações de marcha, em qual marcha e
RPM ele estaria?”

### Modelo longitudinal do Seal

O simulador usado quando não há telemetria real calcula:

1. pedido de torque pelo pedal;
2. torque disponível nos eixos dianteiro e traseiro conforme a velocidade;
3. limite de potência do motor elétrico;
4. limite de aderência;
5. freio solicitado;
6. regeneração ao soltar o pedal no modo de simulação;
7. arrasto aerodinâmico;
8. resistência de rolamento;
9. aceleração e nova velocidade.

Os valores-base atuais são:

| Item | Valor |
|---|---:|
| Torque máximo de referência | 670 Nm |
| Potência máxima | 390 kW |
| Rotação máxima do motor elétrico | 16.000 RPM |
| Redução do motor para a roda | 10,81:1 |
| Eficiência do conjunto | 92% |
| Pico de torque na roda dianteira | 3.170 Nm |
| Pico de torque na roda traseira | 3.975 Nm |
| Limite de aceleração por aderência | 10,0 m/s² |
| Massa | 2.185 kg |
| Fator equivalente das massas giratórias | 1,10 |
| Raio da roda | 0,347 m |
| Área aerodinâmica efetiva | 0,504 m² |
| Coeficiente de rolamento | 0,010 |
| Velocidade máxima | 190 km/h |
| Desaceleração regenerativa simulada | 2,50 m/s² |
| Freio máximo modelado | 11,2 m/s² |

As curvas de torque foram digitalizadas de uma referência de 180 km/h. O eixo horizontal abaixo é a
fração dessa velocidade de referência e o vertical é a fração do pico daquele eixo:

| Fração da velocidade | Dianteiro | Traseiro |
|---:|---:|---:|
| 0,000 | 1,000 | 1,000 |
| 0,156 | 0,989 | 0,992 |
| 0,322 | 0,906 | 0,994 |
| 0,394 | 0,761 | 0,886 |
| 0,461 | 0,622 | 0,772 |
| 0,561 | 0,459 | 0,630 |
| 0,639 | 0,366 | 0,553 |
| 0,706 | 0,309 | 0,502 |
| 0,761 | 0,266 | 0,461 |
| 0,861 | 0,221 | 0,398 |
| 0,933 | 0,190 | 0,362 |
| 1,000 | 0,169 | 0,333 |

O pedal também tem uma curva, para não transformar 10% físicos em apenas 10% de torque:

| Pedal físico | Pedido normalizado |
|---:|---:|
| 0% | 0% |
| 10% | 13% |
| 25% | 31% |
| 50% | 60% |
| 75% | 84% |
| 100% | 100% |

O pedal cru ainda é disponibilizado para a lógica sonora que precisa reproduzir o comportamento
autoral do Assetto Corsa. A versão filtrada é usada no movimento do Seal. Assim, suavizar a física
não apaga uma mudança rápida de pedal necessária para disparar uma válvula de alívio ou um estouro.

### Por que a velocidade passa por um estimador

A API da BYD entrega velocidade em quilômetros por hora inteiros. Se o conta-giros usasse diretamente
0, 1, 2, 3..., o ponteiro subiria em pequenos degraus e o pitch também pareceria quebrado.

Foi implementado um estimador criticamente amortecido: ele observa a direção e a frequência das
mudanças e reconstrói uma velocidade contínua. Ele usa um deslocamento de até ±0,45 km/h na direção
do movimento, limita a velocidade estimada de mudança a ±45 km/h por segundo e limita o tempo de
resposta configurável entre 0,08 e 0,80 s. Ao chegar realmente perto de zero e ficar 0,55 s sem nova
medição, ele assenta exatamente em zero.

O simulador passa pela mesma quantização inteira antes do estimador. Isso faz o teste em emulador
exercitar o mesmo tipo de entrada imperfeita que o carro real oferece.

## Conta-giros e câmbio automático de apresentação

Cada pacote pode trazer:

- RPM de marcha lenta;
- começo da faixa vermelha;
- RPM do limitador;
- RPM normal de troca para cima;
- limite visual do conta-giros;
- quatro a oito marchas principais;
- relações das marchas e relação final de origem;
- tempo de troca para cima e para baixo;
- conjuntos alternativos de marchas.

O limite técnico do conta-giros é 20.000 RPM. Ele cobre o maior valor oficial encontrado, 19.300 RPM,
com uma pequena margem de validação.

A relação final usada **na apresentação** é recalculada para que a marcha mais alta chegue ao RPM de
troca na velocidade máxima configurada do Seal. As distâncias relativas entre as marchas do carro do
Assetto Corsa são preservadas. Isso mantém a queda de giro característica, sem permitir que o perfil
de um protótipo altere a física real do Seal.

### Fórmula da queda de RPM

Ao subir uma marcha, o RPM em que a próxima marcha deve cair é calculado assim:

```text
RPM de chegada = RPM da troca × relação da próxima marcha ÷ relação da marcha atual
```

Exemplo simples:

```text
troca em 8.000 RPM
segunda = 2,00
terceira = 1,50

chegada na terceira = 8.000 × 1,50 ÷ 2,00 = 6.000 RPM
```

Esse valor é guardado quando a troca para cima acontece. Se ainda não houver um valor guardado,
como depois de trocar de carro, ele é calculado diretamente pelas relações.

### A redução solicitada pelo usuário, sem histerese

Ao soltar o acelerador, a transmissão reduz assim que o RPM da marcha atual alcança ou passa para
baixo daquele RPM de chegada calculado. O código atual usa:

- acelerador filtrado em 10% ou menos para considerar o pedal solto;
- `RPM atual <= RPM de chegada calculado`;
- nenhuma compensação de 150 RPM;
- nenhuma outra histerese de RPM nessa decisão.

Portanto, no exemplo acima, depois de ter subido para a terceira em 6.000 RPM, ela reduzirá ao voltar
a 6.000 RPM ou menos com o pedal solto. Há apenas o tempo mínimo entre trocas, chamado `shift dwell`,
para impedir duas trocas simultâneas. Isso não desloca o ponto de RPM pedido; somente espera a troca
anterior terminar e o intervalo mínimo acabar.

É importante não confundir essa regra com a trava do limitador. O limitador ainda possui 20 RPM de
margem para entrar e 180 RPM para sair. Essa é uma proteção separada para impedir que o estado
“limitando/não limitando” pisque rapidamente. Ela **não** voltou a colocar histerese na redução.

### Outras decisões automáticas

- Com mais de 10% de acelerador, sobe a marcha ao atingir a velocidade que corresponde ao RPM de
  troca.
- Mesmo sem a condição normal, faz uma troca de emergência ao chegar a 98% da faixa vermelha.
- Com mais de 78% de acelerador, pode fazer `kickdown` se estiver pelo menos 10 km/h abaixo da antiga
  velocidade de troca e a marcha menor não ultrapassar a faixa vermelha.
- A razão realmente muda em 38% do tempo total da animação/evento de troca.
- Os tempos padrão, quando não há perfil importado, são 60 ms para cima, 150 ms para baixo e 150 ms
  de intervalo mínimo.
- Em P, a velocidade simulada fica zero.
- Em P ou N, o RPM fica desacoplado da estrada: sobe em direção ao pedido do pedal com resposta de
  0,55 s e desce para a marcha lenta com resposta de 0,90 s.
- Em D, RPM e marcha ficam acoplados à velocidade estimada.

O catálogo já armazena 358 conjuntos alternativos de marcha em 76 carros, chegando a nove opções em
um carro. A build 33 valida e preserva esses dados, mas ainda usa o conjunto principal; ainda não há
seletor de configuração alternativa na interface.

### O que “igual ao automático do jogo” quer dizer nesta versão

O compilador lê dados reais de relações, limites e duração. O laboratório de computador também
conseguiu consultar a lógica `AUTO_SHIFTER` do executável. Porém, o Android não roda a física completa
do Assetto Corsa nem todas as condições internas de sua IA. Ele implementa o câmbio de apresentação
descrito acima sobre a velocidade do Seal.

Assim, a queda por relação e a redução sem histerese são exatas em relação à regra solicitada. Dizer
que toda decisão de marcha é “bit a bit igual ao Assetto Corsa” seria incorreto; essa equivalência
completa não foi alcançada nem é compatível diretamente com a física independente do Seal.

## Caminho completo do áudio no Android

O áudio passa por estas etapas:

```text
arquivo FLAC privado
       │ abrir uma vez ao selecionar o carro
       ▼
PCM16 estéreo em memória nativa
       │ loops e one-shots já prontos
       ▼
mixer C++ calcula pitch, volume, fase, curvas e efeitos
       │ blocos curtos de amostras
       ▼
limitador de segurança
       │
       ▼
AudioTrack PCM16, 48 kHz, estéreo
       │
       ▼
saída estéreo escolhida pelo sistema do BYD
```

### FLAC é armazenamento, PCM16 é o áudio de trabalho

FLAC não remove informação. O arquivo ocupa menos espaço porque guarda padrões repetidos de forma
compacta; ao ser aberto, volta ao mesmo PCM16 produzido pelo compilador. O encoder é nível 8, a
compressão mais forte do FLAC, mas isso só influencia o tempo de criação do pacote no computador. Não
faz a reprodução perder qualidade.

O Android usa a biblioteca FLAC 1.5.0 compilada dentro do aplicativo. O hash da fonte fixada é
`f2c1c76592a82ffff8413ba3c4a1299b6c7ab06c734dee03fd88630485c2b920` e a licença BSD está incluída
no APK. Isso evita depender do decodificador de mídia específico da central BYD, que poderia ter
comportamento diferente entre versões.

O nível 8 é provado pela procedência do compilador; o bitstream FLAC não oferece ao Android uma
etiqueta confiável “foi nível 8”. No aparelho, a verificação que importa é formato, hash do arquivo,
hash do PCM aberto, quantidade de quadros e limites do loop.

Ao selecionar um carro, uma thread de segundo plano:

1. confere o pacote e o manifesto;
2. abre todos os FLAC necessários daquela família;
3. confere formato, número de quadros e hash do PCM reconstruído;
4. guarda os canais esquerdo e direito em memória nativa, em blocos imutáveis;
5. prepara as estruturas do mixer;
6. executa três aquecimentos de 256 quadros;
7. só então oferece o perfil pronto ao áudio.

Ao mudar a seleção, o código atual primeiro cruza o perfil antigo para um perfil silencioso. Só
publica o áudio novo depois de terminar a validação e a abertura. Isso evita ouvir o carro antigo
enquanto o conta-giros já mostra o novo. A transição acontece no limite de um bloco, com cruzamento
de 30 ms, equivalente a 1.440 quadros em 48 kHz. O perfil antigo é liberado por um trabalhador,
nunca pela thread que precisa alimentar o alto-falante. Se a escolha mudar novamente antes do fim,
a abertura anterior pode ser cancelada.

Arquivos iguais usados por várias funções são abertos uma única vez. Por exemplo, se o mesmo PCM
autoral representa uma troca para cima e outra para baixo, há duas regras de disparo no manifesto,
mas somente uma cópia de samples na memória.

### Limites de memória

O orçamento macio é o menor valor entre 64 MiB e um oitavo da classe de memória informada pelo
Android. O orçamento duro é o menor entre 192 MiB e um quarto dessa classe.

- O limite macio serve para decidir se uma preparação é confortável.
- O limite duro nunca pode ser ultrapassado.
- Não existe cache de um segundo carro decodificado.
- O sistema contabiliza memória ativa, perfil em preparação, perfil antigo esperando liberação e
  reservas feitas antes de abrir.
- O compilador pretende dividir um perfil em janelas de RPM se ele não couber, mas o formato final de
  janelas ainda não foi publicado porque as famílias atuais ficam abaixo do teto de 32 MiB usado na
  auditoria de lançamento.

Existe um defeito conhecido na validação preliminar do manifesto: ela soma cada faixa lógica sem
descontar caminhos FLAC compartilhados. O carregador e a contabilidade real fazem a deduplicação,
mas essa conta anterior pode rejeitar um pacote válido que reutilize muito o mesmo PCM. Isso ainda
precisa ser corrigido antes da validação de 153 famílias.

### O que nunca pode acontecer na thread de áudio

A thread de áudio tem prazo curto e previsível. Por isso, nela não pode haver:

- abertura ou leitura de arquivo;
- decodificação FLAC;
- criação de objetos ou outras alocações;
- trava que possa esperar outra thread;
- formatação de texto ou JSON;
- liberação grande de memória;
- atualização de interface.

Ela recebe números já prontos, escreve em buffers pré-alocados e devolve o próximo bloco. Falhas são
enviadas para uma pequena caixa postal com limite de quatro mensagens, para que registrar um erro
também não trave o som.

## Quais sons o pacote consegue representar

As funções aceitas pelo formato atual são:

| Função | O que representa |
|---|---|
| `IDLE` | motor em marcha lenta, com curva original de RPM e volume |
| `COAST` | camada contínua de motor sem carga/subida de pedal |
| `TEXTURE` | textura contínua autorizada pelo grafo do jogo |
| `INTAKE` | caráter da admissão |
| `EXHAUST` | caráter do escapamento |
| `TURBO` | assobio/pressão contínua do turbo |
| `SPOOL` | função reservada para enchimento/rotação contínua do turbo; o controle autoral distinto ainda é trabalho futuro |
| `BOV` | válvula de alívio |
| `TURBO_TRANSIENT` | evento curto de turbo |
| `TRANSMISSION` | ruído/assobio contínuo da transmissão |
| `LIMITER` | comportamento autoral no corte de giro |
| `SHIFT_UP` | som de troca para cima |
| `SHIFT_DOWN` | som de redução |
| `OVERRUN` | combustão/escapamento ao aliviar |
| `POP` | estouro curto |
| `BANG` | estouro mais forte |
| `CRACK` | estalo seco |
| `ENGINE_TRANSIENT` | evento curto do núcleo do motor |

`LOAD` não está nessa enumeração. O parser também rejeita um manifesto que tente introduzi-lo.

IDLE está incluído de verdade: a auditoria final exigirá que toda família tenha uma faixa IDLE
autoral audível no seu RPM de marcha lenta. IDLE não é um tom sintético gerado pelo app. Quando a
mesma fonte FMOD contém um ramo de pedal subindo e um ramo aceitável de marcha lenta, o compilador
isola somente a projeção de marcha lenta. Há 11 receitas desse tipo no plano de trabalho.

Retirar LOAD não significa que o pedal deixou de influenciar todo o som. Curvas autorais permitidas
podem mudar o volume de COAST, TEXTURE, admissão, escapamento, turbo e efeitos. A diferença é que a
gravação classificada como o programa explícito de motor sob carga nunca é capturada, armazenada ou
aberta pelo aplicativo.

## Como os loops mudam de RPM

Cada faixa contínua tem um **RPM raiz**. É o RPM em que a gravação toca na velocidade original. Se a
gravação tem raiz 4.000 RPM e o painel pede 6.000 RPM, a forma comum de pitch usa a razão 6.000 ÷
4.000. O som toca mais rápido e mais agudo, como o `autopitch` do FMOD.

Além do modo comum, cinco fontes oficiais usam uma propriedade FMOD diferente, chamada internamente
de `propertyIndex 1`. Para elas, o pitch é uma curva autoral relativa, limitada entre 0 e 16. Essa
curva **substitui** a razão RPM/raiz; multiplicar as duas deixaria o pitch errado. As cinco foram
medidas e certificadas: uma no Ferrari SF15-T e quatro no Alfa Romeo 33 Stradale.

O Android usa interpolação cúbica para ler posições fracionárias entre samples. Isso evita o degrau
mais áspero de escolher apenas o sample inteiro mais próximo. O fim do loop é exclusivo: se o fim é
1000, o último quadro incluído é 999. Os limites vêm do compilador, e a fase continua mesmo quando a
voz é virtualizada, conforme a política daquele tipo de fonte.

As curvas de volume podem usar:

- RPM do motor;
- pedal/acelerador autoral;
- velocidade do eixo da transmissão em radianos por segundo convertida para RPM;
- estado de troca;
- pressão/decay de turbo;
- regiões com mínimo e máximo inclusivos ou exclusivos.

Foram inventariadas 1.826 fontes contínuas candidatas: 1.720 de motor e 106 de transmissão. Destas,
1.821 usam curvas diretas e cinco usam a propriedade especial de pitch. Todas as 153 famílias têm
algum suporte contínuo mapeado. Isso é uma auditoria estrutural; cada captura final ainda precisa ser
confirmada contra o FMOD antes de virar pacote de lançamento.

## O mixer nativo

O mixer em C++ combina loops e one-shots em blocos pré-alocados. Ele mantém suavizações separadas
para RPM, pedal, programa principal, ligar/desligar e ganho de camada. Os valores-base ajustáveis são:

| Ajuste | Padrão |
|---|---:|
| Volume geral do perfil | 0,72 |
| Suavização de RPM para o áudio | 16 ms |
| Suavização de pedal para camadas | 10 ms |
| Transição do programa | 8 ms |
| Transição ao ligar/desligar | 10 ms |
| Transição de volume de uma camada | 12 ms |

Há também uma atenuação comum de 0,65 antes do volume do carro. O compilador calibra a família para
que a combinação padrão fique abaixo de −3 dBFS; o alvo de captura é −3,1 dBFS e somente atenua, nunca
amplifica para alcançar o alvo. No fim existe um limitador suave com joelho em −3 dBFS e teto de
−1 dBFS. Um contador registra quantas amostras passaram da faixa antes do limitador, porque esconder
clipping com o limitador não resolve uma mixagem ruim.

O ganho geral pode ser ajustado entre 0 e 1,20. O ganho individual de uma faixa pode ir de 0 a 8,
para permitir inspeção, mas uma regulagem extrema pode obviamente mudar o equilíbrio originalmente
calibrado.

### Quantas vozes podem existir

Uma **voz** é uma reprodução independente de um loop ou one-shot. O sistema aceita até 2.048 vozes
lógicas e mantém até 256 vozes reais sendo misturadas. Uma voz lógica virtual continua guardando
tempo e fase, mas não gasta a mesma mistura completa enquanto não há espaço ou enquanto está
inaudível.

A escolha considera primeiro a prioridade autoral, depois a audibilidade. Empates são resolvidos de
forma determinística pela idade/sequência e índice. Testes com FMOD provaram prioridade antes de
audibilidade em classes medidas e promoção após liberar um canal, mas não provaram o desempate exato
universal do FMOD. Portanto, o árbitro Android é uma aproximação deliberada e previsível, não uma
alegação de comparação bit a bit para todo caso possível.

Se um programa individual tenta ultrapassar seu limite, a nova voz é recusada. Se o limite global é
atingido, a pior voz elegível pode ser roubada. Os diagnósticos contam recusas, roubos, vozes lógicas,
reais e virtuais.

## Saída estéreo e buffer adaptativo

Foram removidos `AUTO`, quad, 5.1, 7.1, espelhamento, duplicação de canais e botão de alternar canais.
A saída atual abre somente:

- PCM16;
- 48.000 quadros por segundo;
- dois canais, esquerdo e direito;
- uso Android de jogo/mídia;
- caminho de baixa latência quando disponível.

Isso corresponde ao comportamento que funcionou corretamente nos canais do BYD Seal e elimina
mistura e cópias de canais sem benefício.

O buffer começa com alvo de 50 ms e pode variar entre 30 e 80 ms:

- se há underrun ou pouca fila, cresce 10 ms;
- depois de 60 segundos limpos, pode diminuir 5 ms;
- não muda mais de uma vez por minuto.

Um `underrun` acontece quando o Android pede som e a fila ficou vazia; o resultado costuma ser um
clique, estalo ou silêncio muito curto. Um buffer maior protege contra isso, ao custo de mais atraso.
O algoritmo busca o menor tamanho que permaneça estável naquele aparelho.

O tamanho de escrita aceito fica entre 64 e 2.048 quadros, com 256 como base de medição. O tamanho
real pode seguir o burst preferido do dispositivo; na sessão atual do emulador ele ficou em 1.088
quadros por escrita.

## Foco de áudio

O Android usa “foco de áudio” para coordenar música, navegação, chamadas e jogos:

- ao receber foco normal, o ganho de foco vai suavemente para 1;
- em `CAN_DUCK`, desce suavemente para 0,20, sem parar fase ou simulação;
- em perda transitória, desce para zero e volta quando o foco retorna;
- em perda permanente, fica em zero e solta o estado de foco;
- depois de perda permanente, só volta quando uma ação legítima pede o foco novamente.

Isso funciona da mesma forma com a tela visível ou escondida. Mudo no início é ainda mais econômico:
se a preferência persistida já diz “mudo”, o serviço não abre FLAC nem cria `AudioTrack`. Se o usuário
apenas aperta mute durante uma sessão, os PCM e o `AudioTrack` permanecem prontos e as fases continuam;
assim unmute é imediato e não reinicia o motor.

## Efeitos: trocas, turbo, estouros e limitador

Um efeito não é simplesmente “toque este WAV”. O banco FMOD pode conter uma árvore com escolhas,
probabilidades, ordem e filhos silenciosos. O formato `.aclib` preserva:

- seleção normal;
- escolha aleatória simples;
- `SMART_RANDOM`, que evita repetições conforme a regra autoral;
- sequência ordenada;
- pesos e probabilidades;
- `SELECT_ALL`, quando vários filhos devem começar juntos;
- regiões de RPM, pedal, troca, boost, BOV, velocidade da transmissão e decay;
- prioridade de cada voz;
- fontes silenciosas que fazem parte da probabilidade.

Um filho silencioso é importante. Imagine uma escolha autoral com 50% de estouro e 50% de silêncio.
Apagar o filho silencioso faria o estouro acontecer 100% das vezes. Por isso, quando o FMOD é
comprovadamente silencioso, o manifesto pode guardar um nó `SILENT_SOURCE`, sem inventar PCM, mas
mantendo a decisão.

### Trocas de marcha

O som de troca dispara quando o pedido de marcha é aceito, antes de a nova razão entrar em 38% do
tempo de troca. Essa ordem veio do comportamento do executável do jogo. O evento anterior ocupado é
encerrado permitindo a cauda, o cursor volta ao início, o estado recebe 0 para redução ou 1 para
subida e o evento começa.

Duas faixas oficiais de subida são realmente silenciosas no caminho final, mesmo existindo no grafo:
Porsche 911 GT3 RS e Toyota TS040. Cada uma foi renderizada duas vezes por 96.000 quadros e produziu
somente zeros. O compilador guarda o certificado de silêncio e não fabrica um ruído de troca.

Os 98 itens classificados como `GEAR_GRIND`, ou arranhado por erro de engate, estão fora do escopo
pedido e não foram fingidos como trocas normais.

### Overrun, pops, bangs e cracks

`Overrun` é a combustão/atividade do escapamento quando o motor é arrastado e o pedal diminui. Pops,
bangs e cracks são apresentados como uma categoria de controle, mas a origem pode ser um nó de
OVERRUN, POP, BANG ou CRACK do próprio banco.

O disparo natural não usa uma regra genérica “soltou, toca”. O pacote traz limites de RPM e limiares
de pedal do carro. O sistema observa o pico recente do pedal, calcula níveis relativos de armar e
disparar e mantém uma janela autoral de 1.000 ms. Só dispara quando as condições do programa daquele
carro são satisfeitas. Isso é a correção conceitual para o caso do Toyota Supra MKIV: o modo isolado
não deve cortar a árvore que contém seus estouros.

O botão de audição solicitado só aparece se existir conteúdo autoral audível. Ao pressioná-lo:

1. o app escolhe um RPM em que a árvore autoral possa ser ouvida;
2. silencia temporariamente os loops contínuos;
3. executa a mesma árvore, com sua ordem, sorteio e filhos silenciosos;
4. ignora apenas os controles de mixer que impediriam a audição pedida;
5. nunca troca o conteúdo por BOV, turbo ou um efeito artificial.

Esse botão demonstra como o efeito soaria **se a condição natural acontecesse**; ele não modifica a
lógica natural nem cria um novo sample.

### Turbo, spool e BOV

O catálogo guarda, por turbina, pressão máxima, wastegate, RPM de referência, gamma, velocidade de
enchimento e esvaziamento e, quando existe, o arquivo controlador dependente de marcha. Há 73 carros
turbo e 11 carros, com 25 arquivos, em que a marcha participa do controlador.

O pedal físico passa primeiro pela curva `throttle.lut` do carro para a física de turbo. O motor de
turbo calcula pressão e expõe boost normalizado. A BOV não é disparada apenas porque o botão do pedal
foi solto: ela observa a transição real de pressão e o limiar autoral da válvula. Já o parâmetro de
acelerador entregue ao evento de motor é o `Car.controls.gas` depois dos auxílios de troca; ele não é
o mesmo número que a curva física do turbo e não recebe falsamente o corte do limitador.

O pacote também consegue guardar o corte de acelerador automático durante uma troca, `autoblip` na
redução e os quatro pontos autorais de assistência. Em 19 carros, o ponto 2 é menor que o ponto 1; por
isso eles são preservados na ordem escrita pelo jogo, em vez de serem reordenados como uma curva
comum. A avaliação usa o primeiro limite superior aplicável e combina auxílios pelo máximo antes de
passar pela sequência física correta.

Os 171 transientes de turbo/BOV foram fechados com prova individual: 160 são audíveis e 11 são
silêncios autorais que permanecem nas árvores de escolha. O ganho autoral medido pode ultrapassar 1;
o maior foi 37,5016948 no Porsche 919 Hybrid 2016. Por isso o formato permite até 38 e não corta
silenciosamente o valor para 1.

Já os **66 loops contínuos de turbo**, presentes em 52 famílias, ainda não têm a certificação final.
O inventário conhece 54 loops persistentes de timeline e 12 loops dentro de uma região de boost, 60
aparentemente audíveis e seis roteados como silêncio. Mas ainda falta provar para todos como fase,
volume zero, retorno do zero e propriedades de pitch se comportam. A build 33 possui a base genérica
de turbo, porém não implementa como “exata” essa semântica contínua ainda não congelada. Isso é um dos
dois maiores bloqueios do catálogo final.

### Limitador

O limitador não é um clique repetido por um timer inventado no Android. Foram certificados 73
programas de limitador:

- 48 one-shots periódicos na linha do tempo persistente;
- 7 one-shots dentro de uma região de decay;
- 18 loops dentro de uma região de decay;
- 70 fontes audíveis;
- três fontes autorais silenciosas.

O comportamento medido no executável inicializa um contador em 10, soma o tempo a cada atualização e
zera o contador quando há corte. Enquanto o valor é até 10, o evento continua desejado; acima de 10,
ele para permitindo a cauda. Um novo corte durante um evento ativo reinicia apenas o decay, mantendo
timeline e fase. Um corte depois de parado volta a timeline para zero e inicia novamente.

Isso está representado no runtime. É diferente da pequena trava de 20/180 RPM que decide quando o
estado de limitador do conta-giros entra e sai.

### Eventos curtos do motor

O grafo encontrou 148 folhas curtas de motor em 35 famílias. O plano para a perspectiva interna
escolheu 60 folhas, em 24 famílias e 50 programas; 88 folhas externas ficam como auditoria de outra
perspectiva, não como mídia deste pacote de cabine.

O comportamento-base foi medido:

- o evento precisa começar dentro da região válida para ficar armado;
- sair da região deixa o áudio já iniciado terminar;
- sair e entrar novamente pode criar outra voz sobreposta;
- pular de um lado ao outro sem uma amostra interna não dispara;
- o pitch automático continua mudando enquanto a voz toca.

Mas várias fontes têm detalhes diferentes quando o ganho chega exatamente a zero. Exemplos já
observados:

- Ferrari FXX K: vai ao zero em 64 quadros e segura a fase depois de 512;
- Ferrari 812 Superfast: mantém 514 quadros, faz fade por 55, chega ao zero em 569 e segura depois de
  1.536;
- Porsche 911 RSR: algumas folhas não criam nova voz ao reentrar;
- uma folha do RSR precisa recuperar com deslocamento fracionário de −0,483 quadro;
- BMW M4 Akrapovic precisa de deslocamento de +48 quadros.

A build 33 entende políticas por fonte, segura fase e prazo de fim natural, cancela um zero breve,
aplica uma correção fracionária uma única vez depois de uma retenção real e suporta “sobrepor” ou
“não criar nova voz”. Os testes nativos dessa infraestrutura passaram 16 de 16.

Contudo, isso ainda não constitui prova das 60 folhas. Doze folhas em sete famílias também precisam
fechar a influência de DSP que não pôde ser atribuída estaticamente. A fonte `aa57` do Lamborghini
Miura produziu seis resultados de fase diferentes em 64 execuções idênticas numa configuração; ainda
não existe uma regra congelada que reproduza essa escolha. Esse é o outro grande bloqueio do catálogo
final.

## Mute, solo e isolamento de efeitos

Há três conceitos diferentes:

- **Mudo geral**: silencia a saída completa. Se já estava salvo antes de iniciar, nem abre o áudio.
- **M** de uma faixa: silencia somente aquela faixa.
- **S** de uma faixa: coloca aquela faixa em solo; quando há qualquer solo, somente as faixas em solo
  participam da mistura contínua.

Além disso, existe o modo solicitado para ouvir os sons que **não** são o motor e a transmissão. Na
interface ele aparece como isolamento de efeitos. Quando ligado:

- o programa contínuo de motor e transmissão é silenciado;
- somente as categorias de efeito marcadas são deixadas passar;
- turbo continua obedecendo ao botão de turbo, pois não é implicitamente apagado pelo mute de
  motor/transmissão;
- uma categoria sem mídia audível não ganha um controle falso;
- o botão de audição pode disparar a árvore autoral para inspeção.

As categorias controláveis são troca, transmissão, overrun/escapamento, turbo+BOV, limitador e
pops/bangs/cracks. O núcleo contínuo do motor não aparece como um “efeito” separável. O problema em
que o Supra tinha estouros no modo normal mas os perdia no isolado foi corrigido conceitualmente ao
fazer a máscara de pops incluir a árvore de OVERRUN e ao manter a audição dentro do mesmo grafo.

Como não há pacote instalado na sessão atual, os botões podem existir na interface, mas nenhum efeito
real pode ser ouvido agora no emulador. A validação auditiva dessa correção depende do pacote final do
Supra ou de um pacote de teste importado.

## Interface da build 33

A interface foi mantida em paisagem e desenhada numa área lógica de 1.920 × 990. Em outras
resoluções, ela preserva essa proporção dentro da área segura do Android e pode deixar faixas vazias
ao redor. Isso impede que recortes da central, barras do sistema ou proporções diferentes deformem o
painel.

Há três telas principais:

- **Classic**: imagem/nome do carro, pedais, P/N/D e o grande conta-giros;
- **Mixer**: seletor pesquisável, importação, favoritos, volume do carro e cartões de cada faixa com
  `M`, `S`, ganho e medidor;
- **Grid**: tela de referência de resolução/alinhamento usada para conferir o painel.

O cabeçalho contém indicador do motor sonoro, origem atual dos dados, seletor Classic/Mixer/Grid,
Debug, efeitos, Tune, modo de entrada e mute geral.

### Correção do trecho que mudava de lugar

O rótulo da origem podia alternar entre textos curtos e longos, como `SIM` e `BYD UNAVAILABLE`, e
empurrar tudo que vinha depois. Ele agora reserva largura fixa de 132 dp, suficiente para a variante
mais larga. No Mixer, a linha superior reserva altura fixa de 118 dp. Assim, mudar estado, nome ou
conteúdo abaixo dela não recalcula a posição daquele bloco do cabeçalho.

### Seletor de carro

O seletor é preguiçoso: só compõe as linhas visíveis, em vez de construir 178 cartões o tempo todo.
Ele mostra estrela, imagem quando instalada, nome e estado de instalação. A busca e a ordem de
favoritos foram descritas acima. Setas no modo Classic permitem ir ao carro anterior ou seguinte.

Uma linha não instalada não pode ser selecionada como carro ativo; ela oferece a importação. Ao
selecionar uma linha instalada, o painel muda os dados de apresentação e o áudio antigo vai para o
perfil silencioso. O som novo só aparece depois que a família correspondente termina de ser validada
e aberta. Assim o app não reproduz o carro antigo sob o nome e o conta-giros do novo.

### Pedais e teclado

Além dos controles de toque, a build de emulador aceita:

- `W` ou seta para cima: acelerador;
- `S`, seta para baixo ou espaço: freio.

Soltar a tecla volta o respectivo pedal a zero. Abrir Tune, Debug ou Efeitos impede esses atalhos de
serem interpretados por baixo do painel aberto.

### Painel Tune

O painel de ajuste ao vivo tem três abas:

- **ENGINE**: máximo do conta-giros, faixa vermelha, limitador sonoro, marcha lenta e RPM de troca;
- **RESPONSE**: ataque e liberação do acelerador, suavização da velocidade BYD, resposta do
  conta-giros, tempo de subida/redução, intervalo entre trocas e cinco fades do áudio;
- **AUDIO**: volume geral, perfil selecionado e gráfico de cobertura das camadas no eixo original de
  RPM do banco.

As mudanças entram imediatamente e são salvas automaticamente. O botão Reset retorna aos padrões.
Esses ajustes mudam apenas o som e a apresentação simulada, nunca os controles físicos do BYD.

### Perspectivas de câmera

O laboratório web do computador possui os estados interno, externo sobre o capô e microfone no
escapamento. A build 33 do Android foi preparada para o pacote de cabine/interno e **não possui** o
seletor dessas três perspectivas. As 88 folhas externas de evento curto e as 978 alternativas de
perspectiva que não entraram no plano são mantidas na auditoria, não no pacote Android atual.

Portanto, a opção de câmeras que existiu no experimento de desktop não deve ser confundida com uma
função já portada ao painel do Seal.

## O que é salvo entre uma abertura e outra

As preferências privadas guardam:

- carro selecionado;
- favoritos;
- ajustes do Tune;
- volume próprio de cada carro;
- mute, solo e ganho de cada faixa, por carro;
- categorias de efeito e isolamento, por carro;
- mute geral;
- estado necessário para decidir se uma sessão interrompida pelo sistema deve restaurar.

Não são guardados:

- acelerador e freio manuais;
- RPM, velocidade e marcha instantâneos;
- fase de um efeito depois de uma parada completa;
- uma alegação de pacote instalado sem o arquivo realmente existir.

Ao restaurar um processo ativo, pedais manuais sempre voltam a zero.

## Diagnóstico de estalos e desempenho

O botão **MARK CRACKLE** registra uma fotografia do instante em que o usuário ouviu um estalo. A
fotografia inclui carro, passos do núcleo, quadros de áudio, RPM, marcha, velocidade, pedal, freio,
estado de som, família/pacote, formato de saída, buffer, underruns, tempos de renderização, coleta de
lixo, wraps de loop, efeitos, vozes, turbo, dados autorais de câmbio/híbrido, memória PCM, pico e
amostras fora da faixa.

O painel também mostra informações do leitor BYD e erros de abertura ou áudio. Os tempos de render
são colocados em um histograma pré-alocado de 128 faixas, cada uma com 50 microssegundos, permitindo
calcular percentil 99 sem armazenar uma lista infinita de medições.

### Uma correção importante sobre persistência do log

O registro `DebugEventLog` atual **não grava continuamente no disco**. Ele guarda somente avisos e
erros recentes em memória e também os envia ao Logcat:

- máximo de 200 entradas;
- máximo de 8.192 caracteres por entrada;
- eventos rotineiros de ciclo de vida e telemetria são omitidos;
- ao morrer o processo, esse histórico em memória é perdido.

Alguns documentos anteriores diziam que existia um log persistente de baixa frequência. Isso não
corresponde ao código da build 33.

Quando o usuário escolhe **Export diagnostics**, aí sim um arquivo JSONL é escrito. JSONL significa
“um objeto JSON por linha”, fácil de abrir ou analisar. O arquivo contém:

1. identificação da build, horário e memória Java;
2. uma fotografia completa do estado;
3. no máximo os últimos 384 KiB do log que ainda estava em memória.

Na interface de produção, o destino é escolhido pelo SAF. Em testes de depuração, pode ser uma pasta
privada do app. O arquivo é limitado; não cresce sozinho para sempre.

Como não é uma série temporal contínua, uma fotografia sozinha não reconstrói todos os milissegundos
anteriores ao estalo. Depois de **MARK CRACKLE**, é importante exportar logo: se o processo morrer ou
mais de 200 mensagens chegarem, a marca pode desaparecer do anel de memória.

### Campos principais do JSONL

Além dos valores de direção, ele registra:

- número da build, Git SHA e horário de compilação;
- memória usada e máxima;
- estado e modo de entrega da telemetria;
- último erro de telemetria;
- serial e estado da troca;
- quadros renderizados e status do áudio;
- tamanho de escrita, buffer, fila e ajustes do buffer;
- underruns de partida e de estado estável;
- p99, menor limite do p99, máximo e quantidade de amostras de render normal, estável e transição;
- quantidade e tempo de coletas de lixo, inclusive bloqueantes;
- foco de áudio;
- wraps de loop e gatilhos de efeitos;
- orçamento e uso de vozes, recusas e roubos;
- ganho do controlador de turbo;
- relações autorais, relação final e marchas alternativas;
- estado de metadados híbridos e peculiaridades;
- pico, over-range e bytes PCM decodificados;
- estado, carro, família e erro da abertura do pacote.

## Privacidade e permissões Android

O manifesto atual:

- declara reprodução de mídia em serviço de primeiro plano;
- pede notificação quando a versão do Android exige;
- pede somente as três leituras BYD já descritas;
- não pede Internet;
- não pede escrita de comandos veiculares;
- desativa backup (`allowBackup=false`);
- desativa tráfego sem criptografia (`usesCleartextTraffic=false`);
- fixa orientação paisagem;
- usa uma única instância da tela principal (`singleTop`).

A seleção de arquivos pelo SAF concede acesso somente aos documentos escolhidos. Os bancos originais
do Assetto Corsa no computador permanecem somente leitura; o compilador trabalha em cópias
temporárias quando precisa isolar uma fonte.

## Como ler o estado das provas do compilador

Para não misturar “o código sabe representar” com “todos os carros estão prontos”, este documento
usa estes níveis:

| Palavra | Significado |
|---|---|
| **Provado** | Existe uma prova imutável, ligada por hashes ao banco, ferramenta, fonte e resultado. |
| **Integrado no plano de trabalho** | O compilador já consegue colocar aquilo no formato, mas o plano global ainda não é final. |
| **Smoke test** | Um ou poucos pacotes provaram que o caminho funciona; não prova o catálogo inteiro. |
| **Interino** | Ferramenta ou schema existe, porém a prova está incompleta ou pode mudar. |
| **Não chegou ao APK** | O material só existe no computador; não está instalado nessa build/emulador. |

### Provas fechadas

| Assunto | Escopo provado | Arquivo e SHA-256 |
|---|---|---|
| Grafo dos bancos | 153 de 153 famílias | `bank-graph-audit-v3/summary.json` — `b45deb37b8567e40a441bc0c915175d81ed79653922d0d41354b093ec3016007` |
| Isolamento de fonte | alterações somente na cópia temporária e 26 renderizações-alvo | `fmod-source-isolation-v1/proof.json` — `13cf54b42b85541f7832f985f6837d4f405936278bb0f5c25b0aa5c84975fb04` |
| Classificação sem nome | 9.450 fontes | `source-role-classification-v2.json` — `72e8c6d7453f74153111d26309914ea4bcd6427e1a8fc1e1cb7ffa16a28cb548` |
| Curvas contínuas estáticas | 1.826 candidatas | `authored-curve-catalog-audit-v1.json` — `3d2d117ab31e6f47c5aacaae0a8ba067503d43c6014b268cd5bed9d9e210d2af` |
| Pitch property 1 | 5 de 5 fontes | `property-one-proof.json` — `f64cb06c135ac29600f1f1465c4e22b66f8e2f5170507f1ea59fa34cb23bc4d7` |
| Cinco disposições difíceis | 4 camadas proibidas + 1 transmissão silenciosa | `static-dispositions-proof.json` — `8eff7b891c6cfd423d2a6093db31152931ada7cb16ce0bbf4b0bffe27266784c` |
| Limitador | 73 de 73 fontes | `limiter-lifecycle-oracle-v1/proof.json` — `973a52f368de23114ed89db668be20f34e8857a27083fde200687af476c62e9a` |
| Turbo/BOV transitório | 171 de 171 folhas | `turbo-transient-oracle-v1/proof.json` — `103fdbc8b79fb564041de368a7ce40b648152d4a89b6563c31372b50479d554b` |
| Prioridade de fonte | 907 observações | `priority-oracle-v1/proof.json` — `b44ad77653d7109392c5377248d05996f1228f219edf00a7c408876e2a97c0d1` |
| Limites globais de voz | 2.048 lógicas/256 reais e regras limitadas | `fmod-global-voice-arbitration-v1/proof.json` — `adf97cad637181250fdf3b5e70ff84b91e92906e3981939d32f6ac4eae034d93` |
| Duas trocas silenciosas | Porsche GT3 RS e Toyota TS040 | `shift-silence-oracle-v1/proof.json` — `0a3fd4a9a057ec1aff3b8e666708e79d975b76138d140ec4fd0747218b5d678b` |
| Inventário híbrido negativo | 12 famílias, nenhum parâmetro de áudio elétrico/ERS separado | `hybrid-property-working.json` — `39d98b0e2a301200ab7977e6a591af94e73a3ab2bed985e34cd981c2d4ff5a9a` |
| Loops privados do Huracán EVO2 | `c1`, `c3` e limitador a 48 kHz | `huracan-trofeo-evo2-private-flac-regression-v2/report.json` — `2641d9d87ec8847bca84733c89805d6a67c3f3bacc1e1c56537ff5d9c74b145f` |

A prova global de vozes é explicitamente limitada: ela não afirma conhecer o desempate universal do
FMOD para qualquer combinação de DSP. Do mesmo modo, a auditoria de curvas encontra e aproxima as
curvas, mas diz que a captura final de cada fonte ainda deve ser comparada ao FMOD.

### Cinco fontes contínuas que não podiam ser tratadas pela etiqueta estática

Testes ao vivo mostraram que quatro camadas chamadas inicialmente de TEXTURE tinham comportamento
de carga escondido no roteamento e, portanto, foram proibidas:

- Lotus 2-Eleven: 24,7826 dB de supressão ao soltar;
- Lotus Elise SC: 55 dB;
- Ferrari F138: 69 dB;
- BMW M235i Racing: silêncio exato ao soltar.

A transmissão do Pagani Huayra BC tem PCM interno não vazio, mas a saída final roteada permaneceu
exatamente silenciosa em 71 observações. Ela recebe certificado `AUTHORED_TARGET_ROUTED_SILENT` em
vez de virar um arquivo falso ou um erro oculto.

### DSPs encontrados

Todos os 153 bancos usam Distance Filter. Também foram encontrados:

- Distortion em 41 folhas de 26 famílias;
- um FMOD Gain;
- um Highpass;
- quatro Lowpass;
- lowpass bruto, equalizador paramétrico, delay, loudness e convolution em estruturas do banco.

O DSP especial da BMW M3 E30 Gr.A está fechado: ganho FMOD versão 65.536, −0,5 dB, sem inversão,
assado uma única vez na captura-alvo. Sete moduladores ligados diretamente a fontes (três ADSR e
quatro Random) e 155 controladores de DSP de banco que o grafo não atribui a uma única fonte ainda
precisam de disposição explícita ou confirmação por PCM. Eles não podem ser descartados só porque o
nome parece irrelevante.

## Estado exato do plano de captura

O arquivo de trabalho atual é:

```text
D:\Users\sgabr\BYDMotorSoundData\aclib\capture-plan-v2-property-working.json
```

- tamanho: 12.123.344 bytes;
- SHA-256 do arquivo: `2954f8048a9da20121d96269afeee95e2cfaf0461e648f99fe569bbefd2022de`;
- SHA-256 canônico do conteúdo: `213343a374fce6ce30450e615bad241735896b6d7fe48a38d41fe2e2179a079c`;
- famílias: 153;
- receitas de captura: 3.272;
- programas de evento: 666.

As receitas atuais são:

| Função no plano | Quantidade |
|---|---:|
| BOV | 143 |
| COAST | 621 |
| ENGINE_TRANSIENT | 60 |
| EXHAUST | 831 |
| IDLE | 233 |
| LIMITER | 73 |
| OVERRUN | 736 |
| SHIFT_DOWN | 175 |
| SHIFT_UP | 159 |
| TEXTURE | 42 |
| TRANSMISSION | 105 |
| TURBO contínuo | 66 |
| TURBO_TRANSIENT | 28 |

Os 171 transientes certificados de turbo podem virar 143 BOVs e 28 faixas com a função
`TURBO_TRANSIENT`, mantendo a árvore completa. Por isso a soma e os rótulos do plano diferem da
contagem bruta de folhas do oracle.

Os 666 programas são: 50 eventos curtos de motor, 73 limitadores, 153 reduções, 152 subidas, 133
eventos ao aliviar e 105 eventos de turbo.

Este arquivo ainda se chama `working` por um motivo: as 60 fontes de transiente de motor e as 66 de
turbo contínuo não estão fechadas num contrato final. Congelar o hash agora apenas eternizaria uma
decisão incompleta.

### Estado dos 41 pacotes existentes

A pasta privada `aclib\packs` contém 41 pacotes V2 e 747 faixas, mas eles foram criados com quatro
versões de plano diferentes:

| Grupo | Quantidade de pacotes |
|---|---:|
| plano iniciado por `e558…` | 22 |
| plano iniciado por `75c507…` | 16 |
| plano iniciado por `b5ce457d…` | 2 |
| plano iniciado por `6ae9a601…` | 1 |

Há ainda um pacote de turbo não verificado em quarentena. Misturar esses arquivos num catálogo seria
perigoso: duas famílias poderiam obedecer contratos diferentes. Por isso eles são material de teste,
não uma biblioteca pronta para importação.

Quatorze pacotes antigos foram reempacotados apenas para corrigir a descoberta
`OVERRUN => popsBangsCracks`; o PCM não mudou. Isso permitiu testar o Android, mas não transforma o
plano antigo em final.

Alguns smoke tests valiosos existem fora desse conjunto:

- Toyota Supra MKIV com árvore de turbo certificada, 24 faixas, incluindo duas folhas audíveis e um
  filho silencioso de `SMART_RANDOM`;
- uma família `bc779…` em processo FMOD novo;
- Ferrari SF15-T com a curva property 1;
- as famílias da troca silenciosa Porsche/Toyota.

Eles provam caminhos isolados. Nenhum prova 153 famílias.

### Regra para reaproveitar mídia

Uma família antiga só pode reaproveitar FLAC se a subárvore canônica do plano de captura for
**idêntica byte a byte**. Qualquer valor diferente exige nova renderização. Mesmo quando o PCM pode
ser reaproveitado, o manifesto e o ZIP são regenerados deterministicamente sob o único hash do plano
final. Pacotes com hashes de plano misturados são rejeitados.

## Como a compilação final foi preparada para continuar

O compilador principal oferece comandos para:

- baixar/preparar ferramentas fixadas;
- gerar catálogo;
- gerar plano e lista de omissões;
- compilar uma família;
- compilar todas;
- validar um pacote;
- auditar os loops privados do Huracán;
- auditar a autoria no SDK/FM0D.

Uma compilação de todas as famílias usa um livro de estado, chamado ledger. Cada família fica como
pendente, em execução, concluída ou falhou, junto com caminho, hash, horário e erro. Se o computador
for interrompido no meio de uma família, a entrada “em execução” volta para pendente com o motivo.
Uma família marcada concluída só é reutilizada depois de conferir novamente o SHA do pacote e a
validação estrita.

O desenho final prevê exatamente quatro grupos determinísticos e sem sobreposição, todos em `D:`,
porque `C:` tinha cerca de 11,8 GiB livres e `D:` cerca de 239 GiB. Um mapa legível por máquina deve
provar que cada uma das 153 famílias aparece uma vez, e somente uma vez. Os ledgers antigos com
sucessos/falhas são histórico de depuração, não contagem de prontidão.

Depois, o auditor de lançamento deve:

1. exigir exatamente 178 carros e 153 famílias;
2. exigir o conjunto exato de pacotes e imagens, sem sobra ou falta;
3. exigir um único hash de catálogo e um único hash de plano;
4. abrir todos os FLAC com a ferramenta fixada;
5. confirmar PCM, quadros, loops e hashes;
6. provar IDLE autoral audível em todas as famílias;
7. procurar qualquer referência proibida a LOAD;
8. verificar picos e memória;
9. rejeitar família acima de 32 MiB de PCM único;
10. gravar um relatório determinístico e atômico.

O maior limite teórico do plano de trabalho atual é 28,04 MiB, na família do Porsche 911 GT1. Isso
ainda precisa ser confirmado abrindo os FLAC finais. O auditor tem nove testes direcionados aprovados,
mas ainda não existe relatório agregado porque a biblioteca completa não existe.

## Os dois bloqueios técnicos que interromperam o lançamento

### 1. Sessenta transientes curtos de motor

Há observações fortes de fontes individuais e o Android já representa seus estados. Falta uma prova
imutável de 60/60, ligada ao plano final. Em particular:

- 12 folhas de sete famílias precisam fechar a influência de DSP por variantes PCM limitadas ou
  provar que não há diferença audível;
- a fonte `aa57` do Miura apresentou um conjunto finito, mas ainda não completamente medido, de
  fases de retorno;
- prioridades variam entre 64 e 128, logo uma prioridade genérica 64 do plano é interina;
- alguns ajustes antigos atingiram boa correlação, mas diferença abaixo da referência menor que
  35 dB, insuficiente para a regra nova.

Pouco antes desta documentação, `tools/probe_fmod_engine_transients.py` recebeu uma checagem de
diferença mínima de 35 dB somente no ramo de deslocamento fracionário. O arquivo passou a ter SHA
`795acdce95d8838fe8e300c0e8d3ed838e43a7ceb2a5cb2edff87cf41663e02e`. Nenhum teste foi executado
depois dessa edição e o ramo de deslocamento inteiro ainda não usa o mesmo limite. Essa mudança é
inconsistente e deve ser uniformizada e testada antes de gerar novas provas.

O arquivo `engine-transient-oracle-v1/partial.json` é obsoleto: contém somente cinco fontes e aponta
para ferramenta e plano antigos. Seu SHA auditado é
`8198ff7206cf385bc6bdb4f4dd206a510ebab1c6d9c5e8b5297a04e29529dddc`. Ele não pode ser promovido a
certificado final.

### 2. Sessenta e seis loops contínuos de turbo

O inventário estático é detalhado, mas a prova total ainda é diagnóstica:

- 66 fontes em 52 famílias;
- 54 loops persistentes da timeline e 12 loops ligados a uma região de boost;
- todas usam propriedade 0 de ganho por boost;
- 59 também usam propriedade 1 de pitch;
- oito também usam propriedade 4 de ganho;
- uma tem controlador adicional sem nome na timeline;
- 61 declaram domínio 0 a 1 e cinco declaram 0 a 1,5;
- 49 fontes audíveis conseguem alcançar ganho exatamente zero.

O ledger antigo selecionou 66, mas só registrou dois sucessos e oito bloqueios antes de parar. Uma
prova crua de duas repetições do SF15-T coincidiu, porém seu próprio campo diz
`exactnessClaim=false`. Não é válido generalizar isso para 66 fontes.

Ainda é preciso fechar entrada/saída de região, parada com fade, reinício em fase zero, retenção ou
avanço de fase no zero, retorno, pitch property 1, gain property 4, controlador sem nome, prioridades,
silêncios e hashes numa única prova imutável. Depois o schema estrito e o runtime Android devem ser
congelados juntos.

## Ferramentas e versões usadas pelo aplicativo Android

A build 33 foi compilada com:

| Componente | Versão/configuração |
|---|---|
| Android Gradle Plugin | 9.3.1 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| `compileSdk` | 37 |
| Android mínimo e alvo | API 25 |
| Java | 11 |
| Android NDK | 28.2 |
| CMake | 3.31.6 |
| Linguagem nativa | C++20 |
| Otimização nativa | `-O3`, sem exceptions e sem RTTI |
| Processadores no APK | arm64-v8a, armeabi-v7a e x86_64 |

A variante `release` ainda está com redução/otimização de bytecode desativada. Isso facilita
depuração, mas não é a configuração final de tamanho e desempenho.

O APK build 33 não contém `.flac`, `.wav`, `.mp3` nem `.ogg`. O único ativo ligado ao áudio é o texto
de licença `assets/third_party_licenses/FLAC.txt`. Portanto, seus aproximadamente 14,85 MB são código,
bibliotecas e interface; não são uma biblioteca sonora escondida.

### Estado de assinatura

Não existe APK `release` da build 33. O candidato de distribuição mais recente encontrado é da
build 31, foi assinado com chave de depuração para instalação local e não é final. Ao verificar esse
candidato explicitamente como API mínima 23, a ferramenta confirma assinaturas v1 e v2; usando a
API mínima real 25, ela escolhe/reportava v2. Isso é detalhe de compatibilidade da verificação, não
uma assinatura de produção.

Uma versão final ainda precisa de número atual, build limpa, otimização decidida e chave de produção
guardada pelo proprietário do app.

## Testes que realmente foram concluídos

### Testes Kotlin/JVM

O último relatório local contém **205 testes aprovados em 30 conjuntos**, sem falha, erro ou teste
pulado. Eles cobrem:

- buffer adaptativo;
- metadados autorais, gas assist e controlador de turbo;
- política de publicação do decoder;
- troca de quadro de áudio sem inconsistência;
- estados de transiente no ganho zero;
- árbitro de 2.048/256 vozes;
- contabilidade de memória nativa;
- abertura de família;
- caixa postal de falhas;
- loops, curvas, efeitos, limitador e mistura no renderer;
- catálogo, importação em lote, JSON e manifesto estritos;
- favoritos e apresentação do catálogo;
- entrada manual/veicular e validação de telemetria;
- serviço, sessão, notificação, remoção dos Recentes e portão de UI;
- câmbio calculado sem histerese;
- persistência e limites dos ajustes.

O arquivo de teste mais novo é de 09:59:52 e o lint de 10:00:19; ambos antecedem a montagem do APK
build 33 por aproximadamente 20 minutos. Eles são evidência muito próxima da árvore atual, mas não
uma repetição limpa feita depois de gerar exatamente o APK cujo hash está no começo deste documento.

### Lint

O relatório de análise estática tem **zero erros e 36 avisos**. Aviso não é o mesmo que falha, mas
também não deve ser descrito como “zero problemas”. Esses 36 itens ainda merecem revisão antes do
lançamento.

### Testes instrumentados no emulador

Houve um conjunto completo anterior com 26 de 26 testes instrumentados aprovados. Depois da mudança
de imagem neutra, o teste específico `missingPrivatePreviewUsesNeutralPlaceholder` foi executado
sozinho e passou 1 de 1 em 28 de agosto de 2026 às 10:11:27 −03:00.

Não há um único relatório posterior provando os **27 juntos** exatamente sobre a build 33. A
conclusão honesta é: 26 passaram antes da última mudança, o novo teste passou isoladamente, e o
conjunto completo atual ainda deve ser reexecutado.

Os testes instrumentados existentes verificam, entre outros pontos:

- foco de áudio;
- paridade do mixer C++;
- imagem ausente;
- favoritos;
- roteiro do `DriveController`;
- declarações do serviço no manifesto;
- ciclo básico da Activity.

### Medições nativas

- Paridade de transição fracionária: 16 de 16 casos passaram.
- Transição de ganho zero: p99 aproximado de 41 µs por bloco de 256, zero alocações no teste.
- Maior pacote de teste atual, com 32 faixas: p99 aproximado de 924 µs por 256 quadros, zero
  alocações.
- Meta futura: p99 abaixo de 1,5 ms por 256 quadros em todos os pacotes finais.

Essas medições são úteis, mas foram feitas com pacotes de teste; não substituem a varredura final de
153 famílias no hardware BYD.

Um relatório antigo da build 27 mencionava 22 famílias e “100 trocas”, mas foi invalidado: uma
condição de corrida permitia que o estado `ACTIVE` do carro anterior satisfizesse a espera do próximo.
Ele ainda mostra que importação em lote e o caminho de tempo funcionavam, porém **não prova 100
trocas concluídas nem estabilidade de memória**. O roteiro corrigido agora exige ID selecionado,
`ACTIVE`, `pack_car` correto, bytes decodificados maiores que zero e avanço de quadros depois da
publicação. Esse roteiro corrigido ainda não produziu um relatório final de 100 ou 1.000 trocas.

Há evidências históricas úteis, mas não finais: a build 20 passou cenários de Home/retorno,
restauração por pressão de processo, Stop e Recents sem ressuscitar o serviço; a build 14 tocou em
baixo volume estéreo sem underrun ou over-range. Elas servem como regressão, não como aceitação da
build 33 e dos futuros 153 pacotes.

### Testes do compilador

Os grupos Python de pacote/lançamento/agregação reportaram 41 de 41 testes direcionados aprovados; o
auditor agregado recebeu depois nove testes específicos, também aprovados. As suítes focadas de
grafo, isolamento, curvas, limitador e turbo transitório passaram seus gates individuais.

Não existe um teste final “153 pacotes reais aprovados” porque o plano ainda não foi congelado e os
153 pacotes ainda não foram gerados. Testar a ferramenta e testar o produto de catálogo completo são
duas coisas diferentes.

## O que o emulador está executando agora

Na fotografia obtida em 28 de agosto de 2026 às 10:35:39 −03:00:

| Campo | Valor |
|---|---|
| Carro selecionado | `ks_lamborghini_huracan_st` |
| Tela visível | sim |
| Serviço/núcleo | ativos |
| Passos do núcleo | 168.294 |
| Quadros de áudio processados | 40.391.680 |
| Fotografias de UI construídas | 45.835 |
| Entrada | AUTO |
| Posição | D |
| RPM/marcha/velocidade | 900 RPM, 1ª, 0 km/h |
| Pedais | acelerador 0, freio 0 |
| Som geral | ligado |
| Família e carro de pacote | nenhum |
| Pacote | `BUILT_IN` — somente metadados básicos |
| FLAC decodificado | 0 bytes |
| Imagem privada | ausente |
| Saída | 48 kHz, 1.088 quadros por escrita |
| Buffer | 4.360 quadros, alvo 30 ms, fila 4.360 |
| Underruns | 0 de partida, 0 estáveis |
| Loops e efeitos | 0 e 0 |
| Vozes | 0 lógicas, 0 reais, 0 virtuais |
| Pico e over-range | 0 e 0 |

O status `ACTIVE` quer dizer que o mecanismo/saída está rodando. Não quer dizer que existe som: sem
pacote, ele escreve silêncio. O p99 de 800 µs e máximo de 16.552 µs dessa sessão medem principalmente
o caminho vazio e incluem condições de emulador; não demonstram desempenho com um carro completo.

Os diretórios privados do app no emulador não contêm catálogo nem pacote importado. A permissão de
notificação está concedida e `MainActivity` ficou no topo quando a conferência terminou.

## O que foi removido do caminho antigo

O código antigo mantinha dois carros escritos à mão e enumerava WAV por WAV no Gradle. Essa rota foi
retirada para evitar duas arquiteturas concorrentes. Foram removidos:

- `AdditionalCarProfiles.kt`, fábrica de perfis simples;
- `HuracanProfile.kt`, perfil manual de 24 camadas;
- `AudioMixModeRepository.kt`, alternância da mistura LOAD/COAST antiga;
- `SelectedCarRepository.kt`, seletor limitado anterior;
- `WavPcmDecoder.kt`, abertura Java de WAV;
- enumeração explícita de WAVs em `mobile/build.gradle.kts`;
- caminhos de carregamento de LOAD e o modo legado de mistura por pedal;
- opções de saída multicanal e duplicação de canais.

O objetivo não foi apenas renomear arquivos. Catálogo, mídia externa, manifesto estrito, decoder
FLAC nativo e serviço de fundo substituíram a rota antiga.

## Mapa dos principais arquivos da versão atual

### Tela e apresentação

| Arquivo | Responsabilidade |
|---|---|
| `MainActivity.kt` | liga/desliga da interface com o serviço, atalhos e composição das telas |
| `DashboardScreens.kt` | telas Classic/Mixer/Grid e cartões do mixer |
| `SoundEffectsPanel.kt` | categorias, isolamento e audição de pops/bangs |
| `TuningPanel.kt` | ajustes ao vivo e gráficos |
| `DebugPanel.kt` | telemetria, desempenho, mark crackle e exportação |
| `DriveUiLifecycleGate.kt` | permite snapshots somente enquanto existe cliente visível |
| `NotificationPermissionRequestPolicy.kt` | decide quando pedir notificação sem repetir o pedido |

### Catálogo e pacote

| Arquivo | Responsabilidade |
|---|---|
| `OfficialCarIndex.kt` | lista interna dos 178 IDs oficiais |
| `CarCatalog.kt` | modelos do catálogo, busca, favorito e estado instalado |
| `SelectedOfficialCarRepository.kt` | salva carro e favoritos oficiais |
| `SoundFamilyManifestV1.kt` | parser estrito V1/V2 e todas as regras do perfil |
| `StrictJson.kt` | rejeita JSON ambíguo, duplicado ou inesperado |
| `AclibPackImporter.kt` | valida e instala pacote transacionalmente |
| `CatalogImportBatchPolicy.kt` | limita e coordena lotes de importação |

### Áudio

| Arquivo | Responsabilidade |
|---|---|
| `EngineAudioEngine.kt` | saída, foco, buffer e publicação do perfil |
| `EngineAudioFrame.kt` | passagem coerente de números do ciclo para o áudio |
| `EngineSampleProfile.kt` | modelo interno de camadas, curvas e eventos |
| `NativeFlacDecoder.kt` | ponte Kotlin para abrir FLAC em memória nativa |
| `NativeSoundFamilyLoader.kt` | transforma manifesto+PCM em perfil pronto |
| `PlanarPcmData.kt` | posse e liberação dos canais PCM nativos |
| `NativePcmMixer.kt` | ponte para o mixer C++ |
| `SampleEngineRenderer.kt` | curvas, gatilhos, árvores de evento e coordenação do mixer |
| `AdaptiveAudioBuffer.kt` | regra de 30–80 ms |
| `NativeDecodedMemoryLedger.kt` | orçamento ativo/pendente/aposentado/reservado |
| `GlobalVoiceArbiter.kt` | escolhe vozes lógicas, reais e virtuais |
| `EngineTransientEventState.kt` | estados de zero/fase dos transientes curtos |
| `EngineGasAssistRuntime.kt` | corte automático, autoblip e throttle map |
| `TurboControllerRuntime.kt` | física de turbo e tabelas por RPM/pedal/marcha |
| `RealtimeFailureMailbox.kt` | leva erros para fora da thread crítica |
| `native_flac.cpp` | libFLAC, buffers PCM, mixer, interpolação e limitador |

### Serviço, direção e diagnóstico

| Arquivo | Responsabilidade |
|---|---|
| `DriveRuntimeService.kt` | dono persistente de toda a sessão |
| `DriveController.kt` | ciclo de 200 Hz e coordenação de entradas/simulação/áudio |
| `DriveRuntimeSessionStore.kt` | sessão ativa e marca de parada pelo usuário |
| `DriveRuntimePolicies.kt` | decisões testáveis de início/parada/restauração |
| `DriveRuntimeDiagnostics.kt` | fotografia, mark crackle e JSONL limitado |
| `DebugPackStagingPolicy.kt` | pacote de teste permitido apenas em depuração |
| `EngineSimulation.kt` | física do Seal e câmbio de apresentação |
| `BydSpeedReader.kt` | leitura reflexiva e validada do DiLink |
| `BydGearboxMapping.kt` | converte códigos BYD para P/N/D de apresentação |

### Código nativo, manifesto e ferramentas

- `mobile/src/main/cpp/native_flac.cpp`: decoder e mixer;
- `mobile/src/main/cpp/CMakeLists.txt`: compila libFLAC e o código nativo;
- `mobile/src/main/AndroidManifest.xml`: serviço, áudio e permissões de leitura;
- `mobile/src/debug/AndroidManifest.xml` e `DriveDebugReceiver.kt`: controle ADB só em debug;
- `tools/adb-drive.ps1`: comando individual;
- `tools/start-headless-emulator.ps1`: emulador sem janela;
- `tools/start-visible-emulator.ps1`: emulador visível;
- `tools/run-emulator-acceptance.ps1`: roteiro de aceitação;
- `tools/run-emulator-catalog-sweep.ps1`: varredura do catálogo;
- `tools/run-emulator-car-switch-memory.ps1`: trocas repetidas e memória;
- `tools/test-emulator-acceptance-harness.ps1`: testa o próprio roteiro;
- `tools/sign-release-apk.ps1`: assinatura local/candidata, não chave final de produção.

### Ferramentas principais do compilador no computador

No repositório `assettocorsa`, os módulos centrais incluem descoberta oficial, catálogo, schema,
FLAC, FMOD nativo, renderer silencioso, isolamento, classificação, curvas, turbo, captura por janela,
loops e regressão do Huracán. Os comandos públicos ficam principalmente em:

- `tools/aclib_compiler.py`;
- `tools/aclib_release.py`;
- `tools/audit_aclib_release_catalog.py`.

Há ferramentas separadas para grafo, curvas, classificação, isolamento, direção da fonte,
prioridade, vozes globais, polifonia, silêncio de troca, limitador, turbo transitório, turbo contínuo,
transiente de motor, ramos de fase e captura por janela. O diretório
`tools/fmod_bank_graph_audit/` contém o auditor C# e os patches presos do parser.

## Estado do Git e significado de “build atual”

A árvore Android está suja: há arquivos modificados, removidos e novos ainda sem commit. `git status`
mostrou 107 linhas de alteração/arquivo não rastreado. O resumo dos arquivos já rastreados mostra
aproximadamente 7.633 inserções e 2.684 remoções, sem contar a grande quantidade de arquivos novos
ainda não rastreados.

Isso tem três consequências:

1. `e3da6a4` identifica apenas o commit-base, não todas as mudanças da build 33;
2. reconstruir depois de mais uma edição pode produzir outro APK ainda chamado build 33;
3. o SHA completo do APK e esta data são necessários para identificar exatamente o binário descrito.

Nenhuma mídia privada foi adicionada ao Git. `.gitignore` foi ampliado para manter FLACs, previews,
pacotes e saídas locais fora do repositório.

## Lista completa do que ainda não está terminado

Esta é a fronteira real da build 33. Nenhum item abaixo deve ser escondido por uma frase genérica
como “todos os carros foram adicionados”.

1. **Não há biblioteca final de 153 pacotes.** Há somente 41 pacotes de trabalho com quatro hashes
   de plano misturados.
2. **As 60 folhas `ENGINE_TRANSIENT` não têm prova final 60/60.** Doze ainda dependem de DSP e a
   fonte `aa57` do Miura ainda tem ramos de fase não congelados.
3. **Os 66 loops contínuos `TURBO` não têm prova final 66/66.** A lógica exata de zero, fase, região
   e retorno ainda não foi integrada ao contrato Android final.
4. **O plano e o catálogo são de trabalho.** O catálogo de descoberta é consistente, mas não há uma
   publicação atômica com um único plano final, 153 packs e 178 imagens.
5. **O emulador atual não tem mídia.** Ele mostra os 178 nomes, executa serviço e saída silenciosa,
   mas não permite julgar timbre, pops, turbo ou troca.
6. **Não houve varredura audível final dos 178 carros.** Também faltam o teste estrito de 100 trocas,
   uma campanha maior de 1.000 trocas e cancelamentos rápidos de carregamento.
7. **Não houve aceitação física desta arquitetura no BYD.** Telemetria DiLink, roteamento estéreo,
   foco com outros apps e crackle em condução precisam do carro real.
8. **As três perspectivas não estão no Android.** Cabine é o alvo atual; bonnet e exhaust continuam
   no laboratório de desktop.
9. **Conjuntos de marcha alternativos são somente preservados.** Não existe escolha automática ou
   manual deles na UI.
10. **O log não é persistente.** Mark/export funciona, mas um crash pode apagar o histórico em
    memória anterior à exportação.
11. **A pré-conta de memória pode contar FLAC compartilhado duas vezes.** Isso pode rejeitar um
    pacote válido antes de o carregador deduplicar.
12. **O código ainda não está commitado.** O Git SHA mostrado não identifica sozinho a build.
13. **Não há release build 33 assinada para produção.** O candidato build 31 usa chave de debug.
14. **Sem wake lock, sono profundo pode suspender o serviço.** Isso foi uma escolha, não uma falha
    escondida.
15. **A otimização da variante release está desativada.** Deve ser decidida/testada antes de publicar.
16. **Duas fontes da Ferrari 488 GT3 continuam ambíguas e excluídas.** O oracle de direção foi
    inconclusivo; o sistema falhou fechado, como deveria.
17. **4.153 fontes permitidas ainda podem ter mais de um nome funcional exato.** A decisão principal
    permitido/proibido está estabelecida; COAST versus EXHAUST versus TEXTURE ainda requer fechamento
    onde a distinção altera a interface ou o controle.
18. **Sete moduladores de fonte e 155 controladores DSP de banco exigem disposição final.** Não se
    pode presumir que sejam inaudíveis.
19. **O câmbio Android não é toda a física automática do Assetto Corsa.** Ele é a apresentação
    calculada sobre o Seal.
20. **Há documentação antiga que ficou incorreta.** `docs/persistent-diagnostics.md`, partes de
    `docs/README.md` e `docs/sample-engine-audio.md` ainda descrevem um arquivo persistente com
    rotação/fsync que o código não possui. Outros textos ainda usam nomes da antiga mistura de
    COAST/LOAD. Este documento registra o comportamento real da build 33; os textos antigos precisam
    ser corrigidos ou marcados como históricos.
21. **A mudança recente do critério de 35 dB não foi testada e está desigual.** Ela não pode ser a
    base de um certificado novo até ser aplicada uniformemente e ter testes.
22. **O conjunto instrumentado atual não foi rodado todo junto.** Existe evidência 26/26 anterior e
    1/1 posterior, não um relatório único 27/27 da build 33.
23. **Os números de desempenho atuais não representam o pior carro final.** O perfil silencioso e
    alguns smokes foram medidos; falta medir todos os pacotes reais.
24. **O caminho manual “RUN AUDIO TEST” tem um caso silencioso.** Se o serviço começou com mute
    persistido, esse botão chama a partida da saída sem selecionar o perfil instalado e sem acertar
    todo o estado interno. O comando ADB primeiro liga o som e não sofre exatamente desse caso, mas o
    botão da interface deve ser corrigido.
25. **Som ligado sem pacote ainda abre a saída silenciosa.** Isso foi útil para testar o pipeline,
    porém gasta CPU/foco desnecessariamente quando o carro padrão não tem mídia instalada.
26. **Importação em lote não mostra progresso por pacote nem oferece cancelamento granular.** Cada
    pacote continua atômico e erros parciais são seguros, mas a experiência pode melhorar.
27. **Trocar o catálogo pode deixar pacotes antigos escondidos no disco.** A descoberta não os usa
    quando ficam incompatíveis, porém ainda não os remove automaticamente.
28. **A ausência da ponte ADB em release foi verificada na build 31, não numa release build 33.** O
    código e os source sets indicam a separação correta, mas falta o artefato novo para inspeção.
29. **Todos os arquivos declarados são decodificados ao ativar a família**, inclusive efeitos que o
    usuário deixou desmarcados. Isso deixa qualquer botão instantâneo e evita I/O durante a condução,
    mas usa memória para one-shots atualmente desligados.

## Ordem exata para continuar depois da pausa

O arquivo [resume-ac-catalog-finalization.md](resume-ac-catalog-finalization.md) é o caderno de
retomada. Em termos simples, o trabalho deve continuar nesta ordem:

1. corrigir e testar o mesmo critério de qualidade para deslocamentos inteiros e fracionários;
2. medir e fechar as 60 fontes curtas de motor, inclusive os 12 casos de DSP;
3. resolver a variabilidade de fase `aa57` com uma regra observável ou um modelo estatístico finito,
   determinístico e testável;
4. medir e fechar as 66 fontes contínuas de turbo;
5. congelar o contrato de turbo no manifesto e implementar exatamente o mesmo contrato no Android;
6. gerar um único plano final imutável;
7. criar o mapa de quatro grupos cobrindo as 153 famílias exatamente uma vez;
8. compilar as 153 famílias em `D:` com FMOD silencioso;
9. publicar atomicamente catálogo, pacotes e previews;
10. abrir todo FLAC e executar a auditoria agregada de 178/153, IDLE, no-LOAD, hashes, loops, pico e
    memória;
11. corrigir a dupla contagem de mídia compartilhada no parser Android;
12. importar a biblioteca no emulador e executar todos os testes JVM, lint e os 27 instrumentados;
13. executar 100 e depois 1.000 trocas de carro, cancelamentos rápidos e verificação de que memória
    volta ao patamar esperado;
14. testar Home, troca de app, retorno sem reiniciar fase, Recents, Stop, restauração por pressão de
    processo, tela apagada e mudanças de foco;
15. corrigir os 36 avisos relevantes de lint e a documentação obsoleta;
16. criar build release atual com identidade reproduzível e assinatura apropriada;
17. no BYD, fazer uma varredura de 15 minutos do Huracán, marcando qualquer crackle;
18. no BYD, fazer 60 minutos com carros representativos, alternando primeiro/segundo plano, e exigir
    zero underrun estável;
19. só depois classificar o produto como pronto para uso diário.

## Resumo final em uma frase

A build 33 já é uma nova plataforma Android eficiente, segura e preparada para receber os 178 carros,
mas hoje ela é uma plataforma **sem a biblioteca sonora final**: descoberta, boa parte da engenharia e
várias provas difíceis estão prontas; os dois grupos de comportamento ainda não certificados, a
compilação única dos 153 pacotes e a aceitação real no BYD permanecem pendentes.

## Apêndice A — Os 178 carros reconhecidos

Esta lista foi conferida entre `OfficialCarIndex.kt` do Android e `catalog-v1.json` do compilador.
Os dois arquivos têm os mesmos 178 pares de nome e ID, sem repetição de ID. O agrupamento por marca
serve apenas para facilitar a leitura.

### Abarth (6)

- Abarth 500 SS — `abarth500`
- Abarth 500 SS Step1 — `abarth500_s1`
- Abarth 500 Assetto Corse — `ks_abarth500_assetto_corse`
- Abarth 595 SS — `ks_abarth_595ss`
- Abarth 595 SS Step1 — `ks_abarth_595ss_s1`
- Abarth 595 SS Step2 — `ks_abarth_595ss_s2`

### Alfa Romeo (8)

- Alfa Romeo Giulietta Q.V. — `alfa_romeo_giulietta_qv`
- Alfa Romeo Giulietta Q.V. Launch Ed. — `alfa_romeo_giulietta_qv_le`
- Alfa Romeo 33 Stradale — `ks_alfa_33_stradale`
- Alfa Romeo Giulia Quadrifoglio — `ks_alfa_giulia_qv`
- Alfa Romeo Mito QV — `ks_alfa_mito_qv`
- Alfa Romeo 155 TI V6 — `ks_alfa_romeo_155_v6`
- Alfa Romeo 4C — `ks_alfa_romeo_4c`
- Alfa Romeo GTA — `ks_alfa_romeo_gta`

### Audi (10)

- Audi S1 — `ks_audi_a1s1`
- Audi R18 e-tron quattro 2014 — `ks_audi_r18_etron_quattro`
- Audi R8 LMS Ultra — `ks_audi_r8_lms`
- Audi R8 LMS 2016 — `ks_audi_r8_lms_2016`
- Audi R8 Plus — `ks_audi_r8_plus`
- Audi Sport quattro — `ks_audi_sport_quattro`
- Audi Sport quattro S1 E2 — `ks_audi_sport_quattro_rally`
- Audi Sport quattro Step1 — `ks_audi_sport_quattro_s1`
- Audi TT Cup — `ks_audi_tt_cup`
- Audi TT RS (VLN) — `ks_audi_tt_vln`

### BMW (18)

- BMW 1M — `bmw_1m`
- BMW 1M Stage 3 — `bmw_1m_s3`
- BMW M3 E30 — `bmw_m3_e30`
- BMW M3 E30 Drift — `bmw_m3_e30_drift`
- BMW M3 E30 GrA 92 — `bmw_m3_e30_dtm`
- BMW M3 E30 grA — `bmw_m3_e30_gra`
- BMW M3 E30 Step1 — `bmw_m3_e30_s1`
- BMW M3 E92 — `bmw_m3_e92`
- BMW M3 E92 Drift — `bmw_m3_e92_drift`
- BMW M3 E92 S1 — `bmw_m3_e92_s1`
- BMW M3 GT2 — `bmw_m3_gt2`
- BMW Z4 — `bmw_z4`
- BMW Z4 Drift — `bmw_z4_drift`
- BMW Z4 GT3 — `bmw_z4_gt3`
- BMW Z4 Step1 — `bmw_z4_s1`
- BMW M235i Racing — `ks_bmw_m235i_racing`
- BMW M4 — `ks_bmw_m4`
- BMW M4 Akrapovic — `ks_bmw_m4_akrapovic`

### Chevrolet (2)

- Chevrolet Corvette C7 Stingray — `ks_corvette_c7_stingray`
- Chevrolet Corvette C7R — `ks_corvette_c7r`

### Ferrari (20)

- Ferrari 312T — `ferrari_312t`
- Ferrari 458 Italia — `ferrari_458`
- Ferrari 458 GT2 — `ferrari_458_gt2`
- Ferrari 458 Italia Stage 3 — `ferrari_458_s3`
- Ferrari 599XX EVO — `ferrari_599xxevo`
- Ferrari F40 — `ferrari_f40`
- Ferrari F40 Step3 — `ferrari_f40_s3`
- Ferrari LaFerrari — `ferrari_laferrari`
- Ferrari 250 GTO — `ks_ferrari_250_gto`
- Ferrari GTO — `ks_ferrari_288_gto`
- Ferrari 312/67 — `ks_ferrari_312_67`
- Ferrari 330 P4 — `ks_ferrari_330_p4`
- Ferrari 488 GT3 — `ks_ferrari_488_gt3`
- Ferrari 488 GTB — `ks_ferrari_488_gtb`
- Ferrari 812 Superfast — `ks_ferrari_812_superfast`
- Ferrari F138 — `ks_ferrari_f138`
- Ferrari F2004 — `ks_ferrari_f2004`
- Ferrari FXX K — `ks_ferrari_fxx_k`
- Ferrari SF15-T — `ks_ferrari_sf15t`
- Ferrari SF70H — `ks_ferrari_sf70h`

### Ford (3)

- Ford Escort Mk1 — `ks_ford_escort_mk1`
- Ford GT40 — `ks_ford_gt40`
- Ford Mustang 2015 — `ks_ford_mustang_2015`

### KTM (1)

- KTM X-Bow R — `ktm_xbow_r`

### Lamborghini (10)

- Lamborghini Aventador SV — `ks_lamborghini_aventador_sv`
- Lamborghini Countach — `ks_lamborghini_countach`
- Lamborghini Countach S1 — `ks_lamborghini_countach_s1`
- Lamborghini Gallardo Superleggera — `ks_lamborghini_gallardo_sl`
- Lamborghini Gallardo Superleggera S3 — `ks_lamborghini_gallardo_sl_s3`
- Lamborghini Huracan GT3 — `ks_lamborghini_huracan_gt3`
- Lamborghini Huracan Performante — `ks_lamborghini_huracan_performante`
- Lamborghini Huracan ST — `ks_lamborghini_huracan_st`
- Lamborghini Miura P400 SV — `ks_lamborghini_miura_sv`
- Lamborghini Sesto Elemento — `ks_lamborghini_sesto_elemento`

### Lotus (24)

- Lotus Type 25 — `ks_lotus_25`
- Lotus 3-Eleven — `ks_lotus_3_eleven`
- Lotus 72D — `ks_lotus_72d`
- Lotus 2-Eleven — `lotus_2_eleven`
- Lotus 2-Eleven GT4 — `lotus_2_eleven_gt4`
- Lotus 49 — `lotus_49`
- Lotus 98T — `lotus_98t`
- Lotus Elise SC — `lotus_elise_sc`
- Lotus Elise SC Step1 — `lotus_elise_sc_s1`
- Lotus Elise SC Step2 — `lotus_elise_sc_s2`
- Lotus Evora GTC — `lotus_evora_gtc`
- Lotus Evora GTE — `lotus_evora_gte`
- Lotus Evora GTE Carbon — `lotus_evora_gte_carbon`
- Lotus Evora GX — `lotus_evora_gx`
- Lotus Evora S — `lotus_evora_s`
- Lotus Evora S Stage 2 — `lotus_evora_s_s2`
- Lotus Exige 240R — `lotus_exige_240`
- Lotus Exige 240R Stage 3 — `lotus_exige_240_s3`
- Lotus Exige S — `lotus_exige_s`
- Lotus Exige S — `lotus_exige_s_roadster`
- Lotus Exige Scura — `lotus_exige_scura`
- Lotus Exige V6 Cup — `lotus_exige_v6_cup`
- Lotus Exos 125 — `lotus_exos_125`
- Lotus Exos 125 S1 — `lotus_exos_125_s1`

Os dois itens chamados “Lotus Exige S” têm IDs diferentes. Essa duplicação do nome visível já existe
no catálogo oficial; não é erro desta lista.

### Maserati (7)

- Maserati 250F 12 cylinder — `ks_maserati_250f_12cyl`
- Maserati 250F 6 cylinder — `ks_maserati_250f_6cyl`
- Maserati Alfieri — `ks_maserati_alfieri`
- Maserati GranTurismo MC GT4 — `ks_maserati_gt_mc_gt4`
- Maserati Levante S — `ks_maserati_levante`
- Maserati MC12 GT1 — `ks_maserati_mc12_gt1`
- Maserati Quattroporte GTS — `ks_maserati_quattroporte`

### Mazda (6)

- Mazda 787B — `ks_mazda_787b`
- Mazda MX5 NA — `ks_mazda_miata`
- Mazda MX5 Cup — `ks_mazda_mx5_cup`
- Mazda MX5 ND — `ks_mazda_mx5_nd`
- Mazda RX7 Spirit R — `ks_mazda_rx7_spirit_r`
- Mazda RX7 Tuned — `ks_mazda_rx7_tuned`

### McLaren (7)

- McLaren 570S — `ks_mclaren_570s`
- McLaren 650S GT3 — `ks_mclaren_650_gt3`
- McLaren F1 GTR 96 — `ks_mclaren_f1_gtr`
- McLaren P1 — `ks_mclaren_p1`
- McLaren P1 GTR — `ks_mclaren_p1_gtr`
- McLaren MP4-12C — `mclaren_mp412c`
- McLaren MP4-12C GT3 — `mclaren_mp412c_gt3`

### Mercedes-Benz e Sauber (5)

- Mercedes 190 EVO 2 — `ks_mercedes_190_evo2`
- Mercedes AMG GT3 — `ks_mercedes_amg_gt3`
- Sauber-Mercedes C9 — `ks_mercedes_c9`
- Mercedes SLS AMG — `mercedes_sls`
- Mercedes SLS AMG GT3 — `mercedes_sls_gt3`

### Nissan (4)

- Nissan 370Z Nismo — `ks_nissan_370z`
- Nissan GT-R Nismo — `ks_nissan_gtr`
- Nissan Nismo GT3 — `ks_nissan_gtr_gt3`
- Nissan GT-R R34 V-Spec — `ks_nissan_skyline_r34`

### P4/5 Competizione (1)

- P4/5 Competizione 2011 — `p4-5_2011`

### Pagani (3)

- Pagani Huayra BC — `ks_pagani_huayra_bc`
- Pagani Huayra — `pagani_huayra`
- Pagani Zonda R — `pagani_zonda_r`

### Porsche (27)

- Porsche 718 Boxster S — `ks_porsche_718_boxster_s`
- Porsche 718 Boxster S PDK — `ks_porsche_718_boxster_s_pdk`
- Porsche 718 Cayman S — `ks_porsche_718_cayman_s`
- Porsche 718 RS 60 Spyder — `ks_porsche_718_spyder_rs`
- Porsche 908 LH — `ks_porsche_908_lh`
- Porsche 911 Carrera RSR 3.0 — `ks_porsche_911_carrera_rsr`
- Porsche 911 GT1-98 — `ks_porsche_911_gt1`
- Porsche 911 GT3 Cup 2017 — `ks_porsche_911_gt3_cup_2017`
- Porsche 911 GT3 R 2016 — `ks_porsche_911_gt3_r_2016`
- Porsche 911 GT3 RS — `ks_porsche_911_gt3_rs`
- Porsche 911 R — `ks_porsche_911_r`
- Porsche 911 RSR 2017 — `ks_porsche_911_rsr_2017`
- Porsche 917/30 Spyder — `ks_porsche_917_30`
- Porsche 917 K — `ks_porsche_917_k`
- Porsche 918 Spyder — `ks_porsche_918_spyder`
- Porsche 919 Hybrid 2015 — `ks_porsche_919_hybrid_2015`
- Porsche 919 Hybrid 2016 — `ks_porsche_919_hybrid_2016`
- Porsche 935/78 "Moby Dick" — `ks_porsche_935_78_moby_dick`
- Porsche 962 C Long Tail — `ks_porsche_962c_longtail`
- Porsche 962 C Short Tail — `ks_porsche_962c_shorttail`
- Porsche 911 Carrera S — `ks_porsche_991_carrera_s`
- Porsche 911 Turbo S — `ks_porsche_991_turbo_s`
- Porsche Cayenne Turbo S — `ks_porsche_cayenne`
- Porsche Cayman GT4 Clubsport — `ks_porsche_cayman_gt4_clubsport`
- Porsche Cayman GT4 — `ks_porsche_cayman_gt4_std`
- Porsche Macan Turbo — `ks_porsche_macan`
- Porsche Panamera Turbo — `ks_porsche_panamera`

### Praga (1)

- Praga R1 — `ks_praga_r1`

### RUF (3)

- RUF RT12 R — `ks_ruf_rt12r`
- RUF RT12 R AWD — `ks_ruf_rt12r_awd`
- Ruf Yellowbird — `ruf_yellowbird`

### Scuderia Glickenhaus (1)

- Scuderia Glickenhaus SCG003 — `ks_glickenhaus_scg003`

### Shelby (1)

- Shelby Cobra 427 S/C — `shelby_cobra_427sc`

### Tatuus (1)

- Tatuus FA01 — `tatuusfa1`

### Toyota (9)

- TOYOTA AE86 — `ks_toyota_ae86`
- TOYOTA AE86 Drift — `ks_toyota_ae86_drift`
- TOYOTA AE86 Tuned — `ks_toyota_ae86_tuned`
- Toyota Celica ST185 4WD Turbo — `ks_toyota_celica_st185`
- Toyota GT86 — `ks_toyota_gt86`
- Toyota Supra MKIV — `ks_toyota_supra_mkiv`
- Toyota Supra MKIV Drift — `ks_toyota_supra_mkiv_drift`
- Toyota Supra MKIV Time Attack — `ks_toyota_supra_mkiv_tuned`
- Toyota TS040 Hybrid 2014 — `ks_toyota_ts040`

### Os dois diretórios oficiais excluídos

Estes diretórios estão na lista oficial, mas estão vazios: não têm banco na pasta `sfx`, dados do
carro nem `ui_car.json` com nome oficial. Por isso não podem ser transformados em carros utilizáveis:

- `ks_ferrari_488_challenge_evo`;
- `ks_ferrari_488_gt3_2020`.

Os nomes “Ferrari 488 Challenge Evo” e “Ferrari 488 GT3 2020” podem ser inferidos dos IDs, mas não
foram apresentados como nomes oficiais extraídos porque os arquivos que os confirmariam não existem.

Conferência do apêndice:

- diretórios oficiais: 180;
- carros utilizáveis: 178;
- vazios excluídos: 2;
- famílias: 153;
- IDs repetidos: 0;
- diferenças entre índice Android e catálogo: 0;
- SHA-256 de `OfficialCarIndex.kt`:
  `B8E15988089D5B9200770EF5C635F7D46BBBB71123AAD6B6B29E66EC89D8051D`;
- SHA-256 de `catalog-v1.json`:
  `8FBC33821FE0CFEFC0FA5E37B9C55982ACDC1837004D5CB24DAF2A40B53F0786`.

## Apêndice B — Contagem exata dos testes JVM

Esta tabela permite conferir de onde vêm os 205 testes. Todos estavam verdes no último XML local,
com a ressalva de horário explicada na seção de testes.

| Conjunto | Testes |
|---|---:|
| AdaptiveAudioBuffer | 6 |
| AuthoredCarMetadata | 4 |
| DecodePublicationPolicy | 1 |
| EngineAudioFrame | 1 |
| EngineTransientEventState | 4 |
| GlobalVoiceArbiter | 12 |
| NativeDecodedMemoryLedger | 3 |
| NativeSoundFamilyLoader | 9 |
| RealtimeFailureMailbox | 2 |
| SampleEngineRenderer | 36 |
| TurboControllerRuntime | 16 |
| CarCatalogPresentation | 2 |
| CatalogImportBatchPolicy | 2 |
| CatalogValidation | 41 |
| DebugEventLog | 1 |
| DisplayUnits | 4 |
| DebugPackStagingPolicy | 3 |
| DriveControllerInput | 4 |
| DriveRuntimeDiagnostics | 1 |
| DriveRuntimePolicies | 8 |
| DriveRuntimeSessionPolicy | 7 |
| LayerMixTrackPresentation | 1 |
| DriveUiLifecycleGate | 3 |
| ExampleUnit | 1 |
| NotificationPermissionRequestPolicy | 2 |
| EngineSimulation | 14 |
| BydAvailabilityDiagnostics | 3 |
| BydTransmissionControl | 3 |
| TelemetryValidation | 6 |
| TuningConfig | 5 |
| **Total** | **205** |

Os 27 testes instrumentados presentes no código-fonte se dividem em: dois básicos de Activity, um de
imagem neutra, um de favoritos, dois de roteiro de direção, quatro de manifesto/segurança, um de foco
de áudio e 16 de paridade do mixer nativo.

## Apêndice C — Detalhes de prova que ajudam uma futura retomada

### Onze projeções de marcha lenta compartilhada

Nestes casos, a mesma fonte do banco tinha um caminho crescente e um caminho válido a pedal zero. O
plano mantém somente a captura-alvo de IDLE, sem curva crescente:

- Porsche 911 GT3 RS — fonte iniciada por `ee18e18c`;
- Ferrari F2004 — `b31709b7`;
- Audi Sport Quattro e a família compartilhada Step1 — `f20c16c6`;
- Porsche 911 R — `a1c021b0`;
- Praga R1 — `2fcc6259`;
- Audi R18 e-tron quattro — `b3d321ba`;
- Nissan 370Z Nismo — `7b4997a5`;
- Lamborghini Aventador SV — `bce9f88f`;
- Mazda RX7 Spirit R — `d8c37ac5`;
- Ferrari 330 P4 — `863b0cdb`;
- Maserati Quattroporte GTS — `07ac2d67`.

### As duas ambiguidades da Ferrari 488 GT3

Os GUIDs completos são:

- `072761f3-f125-4e61-99c8-c9b00439e6ed`;
- `9e5a0153-37a0-42fa-9c94-ae6bf7e6e12e`.

O backlog manual tem SHA
`0520c15ad29f0b473e39f2b71d2046669658ea2be7808036a85efa5e50c2a751`; a prova de direção
inconclusiva tem SHA `a9746a25f710c34527588b9e540176bd167541ca3b71acfed10b2cc25f692e6f`.
As duas permanecem excluídas.

### Como as curvas difíceis são aproximadas

O FMOD usa mais de um tipo de segmento. Um segmento exponencial do tipo 0 usa, no auditor:

```text
k = 6,9522 × forma
valor = expm1(k × posição) ÷ expm1(k)
```

O tipo 1 usa uma curva suave com duas alças. A pior diferença normalizada observada foi menor que
`0,00008`, e fora de um fixture limitado pelo PCM16 ficou abaixo de `0,0000071`. As curvas densas
serializadas ficaram abaixo de aproximadamente `0,0002` de erro. O valor bruto −42 representa
silêncio digital; uma tabela de 31 pontos medida na Tatuus representa a cauda muito baixa.

Uma borda rígida de região é representada por no máximo um milionésimo do domínio inteiro do
parâmetro, equivalente a no máximo 0,015 RPM num eixo 0–15.000. Isso é uma aproximação declarada, não
uma igualdade matemática invisível.

### Renderizador silencioso

O compilador usa o FMOD Studio 1.08.12 em `WAVWRITER_NRT`: ele calcula mais rápido que tempo real,
grava WAV e não abre um dispositivo de som. A identidade vem do callback `SOUND_PLAYED`. Se
`common.strings.bank` não oferece o caminho do evento, o programa usa os GUIDs como fallback.

Parâmetros de início podem colocar o evento dentro de uma região e depois parâmetros de captura
podem movê-lo para outro ponto. Cada família da compilação completa é executada num processo Python
novo, oculto, com ordem determinística e limite de seis horas. Isso é uma defesa contra estado
residual; nenhum teste A/B idêntico provou que o FMOD estivesse “vazando” estado. Um problema antigo
da Lotus era na verdade uma sonda 0,0075 RPM fora de uma borda inclusiva.

### Corte de loop e volume

O cortador procura a melhor emenda inteira. Se ela ainda ficar acima de −36 dBFS, pode aplicar uma
transição curta; se continuar acima de −18 dBFS, a captura falha. One-shots só perdem zero digital no
fim, para não cortar a cauda audível. Toda fonte considerada audível deve ter pico finito acima de
−96 dBFS. Um zero só pode ser omitido ou preservado como nó lógico se houver certificado ligado à
fonte.

### Ferramentas FLAC fixadas no compilador

- arquivo-fonte/liberação FLAC 1.5.0: SHA
  `53f1500f0d6e7c61379d7fee50d4a9f7f504c650009506d9ba015530d76c0dde`;
- executável `flac.exe` Win64 selecionado: SHA
  `ff23d9cbc11d18c02f262c3ee455830ea13fbe8d9876249f0bdf101e3ad66709`;
- `metaflac.exe` Win64: SHA
  `4f1653fbfc88a2328f92eb9f856dcf0feb3b303b216d1b573a6d57b21a6e296f`.

O bootstrap recusa caminhos que tentem escapar do arquivo compactado. A codificação usa `--best` e
`--verify`, sem padding e sem data de modificação variável. Depois ela abre o FLAC como PCM little
endian assinado e compara quantidade, pico e hash com o PCM anterior.

### O que os híbridos não mostraram

Nas 12 famílias híbridas, o auditor não encontrou parâmetro de jogo chamado ERS, KERS, elétrico ou
energia, nem sufixo de evento híbrido específico. Os bancos do núcleo usam RPM, pedal e boost comuns.
Isso fecha a pergunta sobre um controlador **sonoro** híbrido separado; não significa que a física
híbrida completa do veículo foi portada para o Seal.

### Contrato estrito quando uma voz chega a volume zero

O manifesto V2 não aceita uma palavra vaga como “virtualize”. Para cada folha curta ele escolhe um
dos três estados:

- não se aplica, porque a fonte nunca alcança zero enquanto ativa;
- chega a zero e depois segura a fase de leitura e o prazo de fim da voz;
- chega a zero, mas continua avançando fase e prazo.

A transição pode ser zero imediato ou “manter o ganho anterior e depois fazer fade linear”. O pitch
durante ela pode seguir o RPM atual ou estar assado na captura. Ao restaurar, pode manter a fase sem
correção ou aplicar um deslocamento fracionário certificado daquela fonte. A reentrada pode criar
outra voz sobreposta ou seguir a exceção do Porsche RSR, que não cria uma nova.

O objeto JSON exige exatamente 16 campos: domínio de quadros, interpolação, ganho inicial/final,
quantos quadros mantém, quantos faz fade, quadro exato de zero, tratamento de pitch e fase, erro
máximo em unidades PCM16, regra para voltar antes da retenção, regra para um novo cruzamento de zero
e deslocamento/limite de restauração. O erro aceito é no máximo uma unidade PCM16, o deslocamento
absoluto máximo é exatamente 512 quadros e o bloco de referência tem 256 quadros estéreo a 48 kHz.

Esse formato determinístico é justamente o motivo para não fingir suporte ao `aa57`: ele ainda não
consegue representar uma escolha entre vários ramos de fase com probabilidades e semente conhecidas.
