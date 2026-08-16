# feat/drive-rpm-tuning-coast-fall — mudanças vs `main`

**Branch:** `feat/drive-rpm-tuning-coast-fall`  
**Base:** `main` (`98283e6`)  
**Commit:** `13d1205`  
**Data:** 2026-08-16

Este documento resume **tudo o que mudou** nesta branch em relação à `main`. Serve como changelog técnico e guia de handoff para quem não acompanhou o desenvolvimento.

---

## Resumo em uma frase

O tacômetro em **D** deixou de seguir a velocidade da estrada e passou a ser um **integrador por força do acelerador**, com sliders dedicados de subida/queda de RPM, todos os delays do programa num só painel, botões de restaurar padrão por seção, labels em português — e o **soft floor de coast foi removido** para que o lift-off respeite de fato a taxa configurada (ex.: 5000 RPM/s).

---

## Por que isso foi feito

Na `main`, o RPM sintético em **D** era **acoplado à velocidade** (`roadCoupledRpmTarget`). Na prática:

- O tacômetro “seguia o carro” em vez de responder ao pedal.
- No BYD Live isso soava errado — o usuário queria sensação de motor a combustão: acelerou → sobe; soltou → cai rápido.
- Os sliders de **SEAL PERFORMANCE** (massa, arrasto, picos de torque etc.) ocupavam espaço na UI mas o foco de tuning ao vivo era outro.
- Delays (ataque do acelerador, tempo de marcha, dwell etc.) estavam espalhados em abas diferentes.
- Havia um **soft floor** no coast que impedia o RPM de cair abaixo de um mínimo ligado à velocidade/marcha — isso anulava a taxa de queda configurada.

---

## 1. Simulação — novo modelo de RPM em D

**Arquivo principal:** `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/simulation/EngineSimulation.kt`

### Antes (`main`)

- Em **D**, o alvo de RPM era sempre `roadCoupledRpmTarget()` (velocidade × marcha × final drive + idle).
- O RPM era suavizado com `approachExp()` em direção a esse alvo.
- Lift-off com retenção de RPM antiga já tinha sido removido, mas o acoplamento à estrada permanecia.

### Depois (esta branch)

- Em **D**, o RPM é integrado a cada passo de 200 Hz por **força**, não por alvo de velocidade:

```text
Subida:  riseRpmPerSec = filteredThrottle × powerFraction(velocidade) × driveMaxRiseRpmPerSec
Queda:   fallRpmPerSec = driveCoastFallRpmPerSec + filteredBrake × driveBrakeExtraFallRpmPerSec
```

- **Lift-off** usa `requestedThrottleOutput` (pedal cru), não o throttle filtrado — resposta imediata ao soltar.
- **Sem soft floor:** no coast o RPM cai até o idle (limite inferior), sem piso artificial ligado à velocidade.
- **Entrada BYD Live:** ao conectar com o carro já em movimento, o RPM **inicial** ainda é semeado com `roadCoupledRpmAtSpeed()` — só na sincronização, não como piso contínuo.
- **Durante troca de marcha:** RPM continua misturando para `rpmTarget` com `syntheticRpmResponseSeconds` (35 ms padrão).
- **N / P:** inalterados — free-rev com inércia fixa (0,55 s subida / 0,90 s descida).

### Novos helpers de potência

- `wheelPowerKwAtSpeed()` — potência na roda na velocidade atual.
- `peakWheelPowerKw()` — pico da curva.
- `wheelPowerFractionAtSpeed()` — fração 0–1 usada para escalar a subida do RPM; abaixo de `driveLaunchFullPowerSpeedKmh` (5 km/h padrão) a fração é **1,0** para largadas fortes parado.

### Upshift de emergência (anti-hunt)

Sem o soft floor, o RPM cai rápido no lift-off. A lógica de upshift foi refinada para **não subir marcha** durante coast:

| Condição | Comportamento |
|----------|---------------|
| RPM alto + acelerador solto | **Não** faz upshift de emergência |
| Acelerador pressionado + RPM ≥ limiar | Upshift de emergência permitido |
| BYD Live: salto brusco de velocidade sem acelerador | Upshift só se aceleração bruta > 2 m/s² **e** tacômetro está > 250 RPM abaixo do acoplado à estrada |

Constantes novas: `EMERGENCY_PROJECTED_RPM_MARGIN`, `EMERGENCY_SPEED_ACCEL_THRESHOLD_MPS2`, `rawExternalAcceleration`.

---

## 2. Configuração e persistência

**Arquivo:** `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/tuning/TuningConfig.kt`  
**Arquivo:** `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/drive/DriveController.kt`

### Novos campos em `EngineTuning` / `EngineProfile`

| Campo | Padrão | O que controla |
|-------|--------|----------------|
| `driveMaxRiseRpmPerSec` | 6000 RPM/s | Velocidade máxima de subida do RPM com acelerador (modo D) |
| `driveCoastFallRpmPerSec` | 5000 RPM/s | Velocidade de queda ao soltar o acelerador |
| `driveBrakeExtraFallRpmPerSec` | 4000 RPM/s | Queda extra ao frear |
| `driveLaunchFullPowerSpeedKmh` | 5 km/h | Abaixo disso, subida usa 100% da força (largada) |

- `CALIBRATION_REVISION` subiu para **7** — prefs antigas são resetadas para defaults na primeira carga.
- Campos **Seal Performance** (massa, arrasto, picos etc.) **continuam persistidos** mas saíram da UI.

### Diagnóstico novo

Em `DriveController.recordDriveDiagnostics()`:

- Evento `lift_off_coast` ao soltar o acelerador em **D** — registra RPM, velocidade, marcha, taxa de coast configurada e fonte de input (SIM / BYD).

---

## 3. Interface de tuning (Live Tuning)

**Arquivo:** `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/TuningPanel.kt`

### Aba VEHICLE — painel DRIVE RPM

Substituiu os sliders de **SEAL PERFORMANCE** na UI (dados ainda existem no backend).

**Sliders de força do RPM:**

| Label na UI (PT-BR) | Campo |
|---------------------|-------|
| Velocidade máxima de subida do RPM com acelerador (modo D) | `driveMaxRiseRpmPerSec` |
| Velocidade de queda do RPM ao soltar o acelerador | `driveCoastFallRpmPerSec` |
| Queda extra de RPM ao frear além do coast | `driveBrakeExtraFallRpmPerSec` |
| Velocidade em que o motor atinge potência plena na largada | `driveLaunchFullPowerSpeedKmh` |

**Seção TEMPOS E ATRASOS** (consolidada aqui; removida das abas Response e Gearing):

| Label na UI (PT-BR) | Campo | Padrão |
|---------------------|-------|--------|
| Suavização do RPM durante troca de marcha | `syntheticRpmResponseMs` | 35 ms |
| Tempo para o acelerador subir | `throttleAttackMs` | 120 ms |
| Tempo para o acelerador cair no lift-off | `throttleReleaseMs` | 90 ms |
| Tempo de resposta do freio | `brakeResponseMs` | 55 ms |
| Duração sonora da subida de marcha | `upshiftDurationMs` | 270 ms |
| Duração sonora da redução de marcha | `downshiftDurationMs` | 340 ms |
| Pausa entre uma troca e a próxima | `shiftDwellMs` | 450 ms |

### Botão DEFAULTS por painel

Todo `PanelCard` ganhou botão **DEFAULTS** no canto superior direito — restaura **só os valores daquele painel** a partir de `TuningConfig.DEFAULT`. O **RESET** global no topo continua resetando tudo.

### Labels em português

Todos os títulos de sliders (`ParameterSlider` / `AudioSlider`) foram reescritos como explicações em **PT-BR** do que cada controle faz, mesmo com o resto da UI em inglês.

### O que não mudou na UI

- **TorquePowerGraph** — intocado.
- Curvas AWD, pedal Sport, marchas, áudio — mantidos; apenas reorganização de delays e botões DEFAULTS.

---

## 4. Correção de bug: coast fall não respeitava 5000 RPM/s

### Problema encontrado nos testes

Com `driveCoastFallRpmPerSec = 5000`:

- **Parado:** queda medida = 5000 RPM/s ✓
- **SIM após acelerar:** RPM parava de cair porque o **soft floor subia** junto com a velocidade virtual ainda crescendo por inércia

### Solução aplicada (e depois simplificada)

Primeiro foi congelada a velocidade de referência do piso no lift-off. Depois, a pedido, o **soft floor foi removido por completo** — solução definitiva e mais simples.

---

## 5. Testes

**Arquivo:** `mobile/src/test/java/com/gabrielpc/enginesoundsimulator/simulation/EngineSimulationTest.kt`  
**Arquivo:** `mobile/src/test/java/com/gabrielpc/enginesoundsimulator/tuning/TuningConfigTest.kt`

### Novos

- `driveModeWotAtStandstillRevsTowardRedline`
- `driveModeLiftOffFallsAtConfiguredRateInSimulator`
- `driveModeLiftOffFallsAtConfiguredRateAtStandstill`
- `driveModeLiftOffAtConstantSpeedFallsTowardIdle`
- `driveModeBrakeFallsFasterThanCoastOnly`
- Sanitizer test para campos `drive*` em `TuningConfigTest`

### Renomeados / atualizados

| Antes | Depois |
|-------|--------|
| `topGearAtConfiguredTopSpeedUsesSoftFloorWithoutThrottle` | `topGearAtConfiguredTopSpeedSyncsRoadCoupledRpmOnEntry` |
| `driveModeCoastRespectsSoftFloorAtSpeed` | `driveModeLiftOffAtConstantSpeedFallsTowardIdle` |
| `driveModeBrakePullsBelowSoftFloor` | `driveModeBrakeFallsFasterThanCoastOnly` |
| `releasingPedalAtConstantSpeedDropsRpmButRespectsSoftFloor` | `releasingPedalAtConstantSpeedDropsRpmTowardIdle` |
| `liftOffWithLiveSpeedHeldDoesNotHuntGears` | Relaxado: pode descer para 2ª marcha, mas não pode fazer hunt para cima |

**Total na branch:** 62 testes unitários passando.

---

## 6. Documentação atualizada

| Arquivo | O que mudou |
|---------|-------------|
| `docs/ui-display-and-simulation-decisions.md` | §3.3 reescrito — integrador throttle-driven, sem soft floor |
| `docs/tuning-interface.md` | Inventário DRIVE RPM + delays consolidados |
| `docs/full-implementation.md` | Descrição do pipeline de RPM em D |
| `docs/llm-handoff.md` | Contexto para próxima sessão de IA |

---

## 7. Build

- `mobile/build-number.properties` incrementado pelo Gradle no assemble (número de build sobe a cada compilação).

---

## 8. Arquivos alterados (diff completo vs `main`)

```
 docs/full-implementation.md                        |   4 +-
 docs/llm-handoff.md                                |  11 +-
 docs/tuning-interface.md                           |  14 +-
 docs/ui-display-and-simulation-decisions.md        |  33 +-
 mobile/build-number.properties                     |   4 +-
 mobile/.../TuningPanel.kt                          | 372 +++++++++++++++------
 mobile/.../drive/DriveController.kt               |  20 ++
 mobile/.../simulation/EngineSimulation.kt           | 117 ++++++-
 mobile/.../tuning/TuningConfig.kt                 |  31 +-
 mobile/.../simulation/EngineSimulationTest.kt      | 133 +++++++-
 mobile/.../tuning/TuningConfigTest.kt              |  15 +
 11 files changed, 594 insertions(+), 160 deletions(-)
```

---

## 9. Comportamento esperado ao testar

1. Abra **Live Tuning → VEHICLE → DRIVE RPM**.
2. Acelere forte em **D** (SIM ou BYD) — tacômetro sobe conforme curva de potência.
3. Solte o acelerador — tacômetro cai na taxa do slider **“Velocidade de queda do RPM ao soltar o acelerador”** (padrão 5000 RPM/s) até o idle.
4. Freie — queda mais rápida pelo slider de freio extra.
5. Toque **DEFAULTS** em qualquer painel — só aquele bloco volta ao padrão de fábrica.
6. No **DEBUG**, ao soltar o pedal em D, aparece log `lift_off_coast`.

---

## 10. O que ficou de fora / limitações

- Campos Seal Performance continuam ocultos na UI (ainda alimentam física EV e gráfico de torque/potência).
- Inércia de N/P continua hardcoded (`NEUTRAL_REV_UP/DOWN_RESPONSE_SECONDS`).
- Filtro de aceleração externa BYD continua em 0,10 s (`EXTERNAL_ACCELERATION_FILTER_SECONDS`).
- Sem soft floor, lift-off agressivo pode **reduzir marcha** mais cedo (ex.: 3ª → 2ª) — comportamento intencional; upshift de hunt para cima foi bloqueado.

---

## Como mergear

```bash
git checkout main
git merge feat/drive-rpm-tuning-coast-fall
```

Ou abrir PR:  
https://github.com/gabrielpc4/pedal-controlled-combustion-engine-sounds-simulator-for-byd/pull/new/feat/drive-rpm-tuning-coast-fall
