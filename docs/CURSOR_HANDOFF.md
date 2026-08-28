# Handoff para Cursor — BYD Motor Sound

## Objetivo do app

Este é um painel Android para BYD DiLink. Ele recebe pedal/velocidade quando o hardware BYD existe, mantém uma física de movimento separada baseada no BYD Seal Performance e usa uma caixa de câmbio de apresentação para tacômetro, mudanças e áudio. O som é uma reprodução por amostras dos sons de carros do Assetto Corsa.

O repositório está deliberadamente com muitas alterações não commitadas. Preserve-as. Não use `git reset`, `git checkout --`, limpeza global nem remova arquivos que você não criou.

## Estado confirmado em 28/08/2026

- A última build gerada foi `engine-sounds-simulator-build-42-debug.apk`.
- O emulador tem o Tatuus FA01 instalado e selecionado (`tatuusfa1`).
- A reprodução do Tatuus foi confirmada: PCM estéreo a 48 kHz, 13 vozes/camadas, 11.136.000 bytes decodificados e zero underruns na verificação curta.
- O pacote do Aventador usado no teste era antigo e incompatível; ele foi removido das pastas de instalação automática do emulador.
- Um pacote da Ferrari SF15-T permanece instalado apenas como outro teste válido; não é o carro selecionado.
- O emulador não possui a API BYD real. A mensagem sobre `BYDAutoSpeedDevice` ausente é esperada nele; use os pedais simulados para testar.

## Arquitetura que não deve ser quebrada

- `drive/DriveRuntimeService.kt`: proprietário exclusivo de telemetria, simulação, câmbio, mixer, `AudioTrack`, foco de áudio e diagnóstico. Continua em segundo plano como foreground service.
- `MainActivity.kt`: somente vincula ao serviço e cria interface/snapshots enquanto visível. Não deve possuir o runtime de áudio.
- `audio/NativePcmMixer.kt`, `NativeSoundFamilyLoader.kt`, `NativeFlacDecoder.kt` e `src/main/cpp/`: decodificação FLAC e mixagem PCM nativas. Não faça alocações ou acesso a arquivos na thread de áudio.
- `catalog/`: catálogo oficial de 178 carros e importação `.aclib` atômica/validada.
- `DashboardScreens.kt`: layout Compose. Alterações de UI recentes elevaram a altura do painel de carro para evitar texto do status encostar no controle de volume. Confira visualmente em landscape após mexer nessa área.

## Instalação automática de pacotes

Ao tocar um carro não instalado, o app chama `selectCarOrAutoInstall` e procura um `.aclib` cujo manifesto contém o ID do carro.

Pastas de entrada:

- externa privada: `Android/data/com.gabrielpc.enginesoundsimulator/files/assetto_sound_library_v1/auto-install`;
- interna privada: `files/assetto_sound_library_v1/auto-install`.

No emulador atual, use a pasta interna ao automatizar por ADB: o processo do app não conseguia ler de forma confiável a área externa pelo shell. Em um aparelho real, a pasta externa privada é a rota prevista para entrega de pacotes.

O progresso atual é exato durante a cópia por bytes; validação FLAC/hash e decodificação são mostradas como uma etapa indeterminada, porque não é seguro inventar uma porcentagem para trabalho nativo variável.

`AclibPackImporter` continua sendo a autoridade: valida ZIP, manifesto, papéis permitidos, FLAC, hash PCM, limites de memória e instalação atômica. Não contorne essas verificações para “fazer tocar”.

Compatibilidade adicionada para pacotes legados:

- schema v2 sem os três campos de pitch (`pitchMode`, `pitchCurve`, `pitchCurveInterpolation`) usa o comportamento padrão RPM/root-RPM;
- schema v1 normaliza a lista declarada de efeitos para os papéis de faixa realmente presentes, pois versões antigas anunciavam lanes não retidas.

Essas exceções ficam em `catalog/SoundFamilyManifestV1.kt`. Schema v2 completo ainda é validado estritamente.

## Carros/pacotes de teste locais

Mídia convertida é privada e gitignored. Não a coloque no APK nem no Git.

- Tatuus: `D:\Users\sgabr\BYDMotorSoundData\aclib-compiler-tatuus-test\packs\668bd5e9af8e0b32cbce0cbea13af16041d92278c6250dc4aadbbfa7dd2bf0ab.aclib`
- Ferrari SF15-T: `D:\Users\sgabr\BYDMotorSoundData\aclib-property-smoke\packs\4e384d921164da0e687dce51e8753ed41ea2c84f1925c6d2e60eb9195e090a74.aclib`
- Pacotes antigos em `D:\Users\sgabr\BYDMotorSoundData\aclib\packs` podem falhar por formato ou metadados antigos. Não use um erro desses para enfraquecer a validação global.

## Como compilar e abrir o emulador

Neste computador não havia Java no PATH. Use o JDK do Unity:

```powershell
$env:JAVA_HOME='C:\Program Files\Unity\Hub\Editor\6000.3.10f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'
.\gradlew.bat :mobile:assembleDebug
```

Instale o APK mais recente encontrado em `mobile\build\outputs\apk\debug`, em vez de adivinhar o número da build. O número muda a cada build por `mobile/build-number.properties`.

```powershell
$apk = (Get-ChildItem mobile\build\outputs\apk\debug -Filter *.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$adb = 'C:\Users\Gabriel\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb install -r $apk
& $adb shell monkey -p com.gabrielpc.enginesoundsimulator 1
```

O receiver ADB em `mobile/src/debug` é somente para debug e permite testes sem tocar na UI. Nunca o leve para a build de produção.

## Próximo trabalho típico

O usuário pretende ajustar bugs e reorganizar a UI. Ao fazê-lo:

1. mantenha as escolhas de carro, favoritos, mute, isolamento e ganho por faixa;
2. teste catálogo sem pacote, instalação automática, troca de carro instalado e retorno do app do segundo plano;
3. mantenha saída apenas PCM16 / 48 kHz / estéreo; não reintroduza quad, 5.1, 7.1 ou caminhos LOAD;
4. mantenha IDLE, COAST, TEXTURE, turbo, transmissão, limiter, shifts, overrun, pops/bangs/cracks quando o perfil os possui;
5. não misture a física EV Seal com os dados de câmbio/tacômetro do carro Assetto Corsa;
6. preserve o downshift automático por RPM calculado sem a antiga compensação/histerese de 150 RPM;
7. para mudanças visuais, tire screenshot no emulador landscape e confira cortes, sobreposição e áreas de toque.

## Leitura recomendada antes de editar

1. `docs/versao-atual-build-33-explicacao-completa.md` — explicação em PT-BR da arquitetura e decisões.
2. `docs/llm-handoff-audio-simulation-and-car-porting.md` — fluxo de áudio/perfis.
3. `docs/llm-handoff.md` — regras do repositório e BYD.
4. Os arquivos citados na seção de arquitetura.

