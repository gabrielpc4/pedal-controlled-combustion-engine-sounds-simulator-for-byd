# Plano: RPM em D por força do acelerador + tuning consolidado

**Branch alvo:** `feat/drive-rpm-tuning-coast-fall`  
**Base:** `main`  
**Tipo:** especificação de implementação (o que precisa ser feito)

Este documento descreve **o trabalho que precisa ser implementado** em relação à `main`. Serve como ticket técnico, guia de implementação e critério de aceite.

---

## Resumo em uma frase

Substituir o RPM acoplado à velocidade em **D** por um **integrador por força do acelerador**, expor sliders dedicados de subida/queda, consolidar todos os delays num painel, adicionar botão DEFAULTS por seção, usar labels em português nos sliders — e **remover o soft floor de coast** para o lift-off respeitar a taxa configurada (ex.: 5000 RPM/s).

---

## Problema atual na `main`

O RPM sintético em **D** segue a velocidade da estrada (`roadCoupledRpmTarget`). Isso causa:

- Tacômetro que “segue o carro” em vez de responder ao pedal.
- Sensação errada no BYD Live — o usuário quer motor a combustão: acelerou → sobe; soltou → cai rápido.
- Sliders de **SEAL PERFORMANCE** (massa, arrasto, picos etc.) ocupam a UI sem serem o foco de tuning ao vivo.
- Delays (ataque do acelerador, tempo de marcha, dwell etc.) espalhados em abas diferentes.
- **Soft floor** no coast impede o RPM de cair abaixo de um mínimo ligado à velocidade/marcha — anula a taxa de queda configurada.

---

## 1. Simulação — novo modelo de RPM em D

**Arquivo:** `mobile/src/main/java/com/gabrielpc/enginesoundsimulator/simulation/EngineSimulation.kt`

### Remover

- Em **D**, o alvo contínuo `roadCoupledRpmTarget()` + `approachExp()` como modelo principal de RPM.
- Qualquer **soft floor** no coast (piso mínimo de RPM ligado à velocidade/marcha durante lift-off).

### Implementar

Integrar RPM a cada passo de 200 Hz por **força**:

```text
Subida:  riseRpmPerSec = filteredThrottle × powerFraction(velocidade) × driveMaxRiseRpmPerSec
Queda:   fallRpmPerSec = driveCoastFallRpmPerSec + filteredBrake × driveBrakeExtraFallRpmPerSec
```

Regras:

- **Lift-off** deve usar `requestedThrottleOutput` (pedal cru), não o throttle filtrado — resposta imediata ao soltar.
- **Sem soft floor:** no coast o RPM deve cair até o idle (único limite inferior via `coerceIn`).
- **Entrada BYD Live:** ao conectar com o carro já em movimento, semear RPM **uma vez** com `roadCoupledRpmAtSpeed()` — só na sincronização, não como piso contínuo.
- **Durante troca de marcha:** misturar RPM para `rpmTarget` com `syntheticRpmResponseSeconds` (35 ms padrão).
- **N / P:** não alterar — free-rev com inércia fixa (0,55 s subida / 0,90 s descida).

### Helpers de potência a criar

| Função | Responsabilidade |
|--------|------------------|
| `wheelPowerKwAtSpeed()` | Potência na roda na velocidade atual |
| `peakWheelPowerKw()` | Pico da curva de potência |
| `wheelPowerFractionAtSpeed()` | Fração 0–1 para escalar subida do RPM; abaixo de `driveLaunchFullPowerSpeedKmh` (5 km/h padrão) usar **1,0** |

### Upshift de emergência — ajustar para evitar hunt

Sem soft floor, o RPM cai rápido no lift-off. A lógica de upshift precisa ser refinada:

| Condição | Comportamento esperado |
|----------|------------------------|
| RPM alto + acelerador solto | **Não** fazer upshift de emergência |
| Acelerador pressionado + RPM ≥ limiar | Upshift de emergência permitido |
| BYD Live: salto brusco de velocidade sem acelerador | Upshift só se aceleração bruta > 2 m/s² **e** tacômetro > 250 RPM abaixo do acoplado à estrada |

Constantes a introduzir: `EMERGENCY_PROJECTED_RPM_MARGIN`, `EMERGENCY_SPEED_ACCEL_THRESHOLD_MPS2`, `rawExternalAcceleration`.

---

## 2. Configuração e persistência

**Arquivos:** `TuningConfig.kt`, `DriveController.kt`

### Novos campos em `EngineTuning` / `EngineProfile`

| Campo | Padrão | O que controla |
|-------|--------|----------------|
| `driveMaxRiseRpmPerSec` | 6000 RPM/s | Velocidade máxima de subida do RPM com acelerador (modo D) |
| `driveCoastFallRpmPerSec` | 5000 RPM/s | Velocidade de queda ao soltar o acelerador |
| `driveBrakeExtraFallRpmPerSec` | 4000 RPM/s | Queda extra ao frear |
| `driveLaunchFullPowerSpeedKmh` | 5 km/h | Abaixo disso, subida usa 100% da força (largada) |

Também:

- Subir `CALIBRATION_REVISION` para **7** — resetar prefs antigas para defaults na primeira carga.
- Manter campos **Seal Performance** persistidos, mas **remover da UI**.

### Diagnóstico a adicionar

Em `DriveController.recordDriveDiagnostics()`:

- Registrar evento `lift_off_coast` ao soltar o acelerador em **D** — RPM, velocidade, marcha, taxa de coast configurada, fonte de input (SIM / BYD).

---

## 3. Interface de tuning (Live Tuning)

**Arquivo:** `TuningPanel.kt`

### Aba VEHICLE — criar painel DRIVE RPM

Substituir os sliders de **SEAL PERFORMANCE** na UI (dados continuam no backend).

**Sliders de força do RPM** (labels em PT-BR):

| Label na UI | Campo |
|-------------|-------|
| Velocidade máxima de subida do RPM com acelerador (modo D) | `driveMaxRiseRpmPerSec` |
| Velocidade de queda do RPM ao soltar o acelerador | `driveCoastFallRpmPerSec` |
| Queda extra de RPM ao frear além do coast | `driveBrakeExtraFallRpmPerSec` |
| Velocidade em que o motor atinge potência plena na largada | `driveLaunchFullPowerSpeedKmh` |

**Seção TEMPOS E ATRASOS** — mover para cá e **remover duplicatas** das abas Response e Gearing:

| Label na UI | Campo | Padrão |
|-------------|-------|--------|
| Suavização do RPM durante troca de marcha | `syntheticRpmResponseMs` | 35 ms |
| Tempo para o acelerador subir | `throttleAttackMs` | 120 ms |
| Tempo para o acelerador cair no lift-off | `throttleReleaseMs` | 90 ms |
| Tempo de resposta do freio | `brakeResponseMs` | 55 ms |
| Duração sonora da subida de marcha | `upshiftDurationMs` | 270 ms |
| Duração sonora da redução de marcha | `downshiftDurationMs` | 340 ms |
| Pausa entre uma troca e a próxima | `shiftDwellMs` | 450 ms |

### Botão DEFAULTS por painel

Cada `PanelCard` deve ter botão **DEFAULTS** no canto superior direito — restaura **só os valores daquele painel** a partir de `TuningConfig.DEFAULT`. O **RESET** global no topo continua resetando tudo.

### Labels em português

Reescrever todos os títulos de sliders (`ParameterSlider` / `AudioSlider`) como explicações em **PT-BR** do que cada controle faz, mesmo com o resto da UI em inglês.

### Não alterar

- **TorquePowerGraph** — intocado.
- Curvas AWD, pedal Sport, marchas, áudio — manter; apenas reorganizar delays e adicionar DEFAULTS.

---

## 4. Bug a corrigir: coast fall não respeita taxa configurada

### Sintoma

Com `driveCoastFallRpmPerSec = 5000`, o usuário reporta que o RPM **não cai** na velocidade esperada ao soltar o acelerador.

### Causa provável (validar com testes + logs)

- **Soft floor** sobe junto com a velocidade (especialmente no SIM, onde o carro virtual ainda ganha velocidade por inércia após lift-off).
- O piso “puxa” o RPM de volta para cima e anula a queda configurada.

### Solução

Remover o soft floor por completo. O RPM no coast deve cair na taxa configurada até o idle.

Validar com testes que medem a taxa real de queda em:

- Parado (WOT → lift-off)
- SIM após acelerar
- BYD Live com velocidade externa constante

---

## 5. Testes a criar / atualizar

**Arquivos:** `EngineSimulationTest.kt`, `TuningConfigTest.kt`

### Criar

- `driveModeWotAtStandstillRevsTowardRedline`
- `driveModeLiftOffFallsAtConfiguredRateInSimulator`
- `driveModeLiftOffFallsAtConfiguredRateAtStandstill`
- `driveModeLiftOffAtConstantSpeedFallsTowardIdle`
- `driveModeBrakeFallsFasterThanCoastOnly`
- Sanitizer para campos `drive*` em `TuningConfigTest`

### Atualizar (remover expectativas de soft floor)

| Teste atual na `main` | Ajuste necessário |
|----------------------|-------------------|
| `topGearAtConfiguredTopSpeedUsesSoftFloorWithoutThrottle` | Renomear; validar apenas seed de RPM na entrada BYD |
| `driveModeCoastRespectsSoftFloorAtSpeed` | RPM deve cair em direção ao idle, sem piso |
| `driveModeBrakePullsBelowSoftFloor` | Freio deve cair mais rápido que coast puro |
| `releasingPedalAtConstantSpeedDropsRpmButRespectsSoftFloor` | Remover assertiva de piso mínimo |
| `liftOffWithLiveSpeedHeldDoesNotHuntGears` | Permitir downshift (ex.: 3→2); proibir hunt para cima |

---

## 6. Documentação a atualizar

| Arquivo | O que registrar |
|---------|-----------------|
| `docs/ui-display-and-simulation-decisions.md` | §3.3 — integrador throttle-driven, sem soft floor |
| `docs/tuning-interface.md` | Inventário DRIVE RPM + delays consolidados |
| `docs/full-implementation.md` | Pipeline de RPM em D |
| `docs/llm-handoff.md` | Contexto para próxima sessão |

---

## 7. Arquivos previstos no diff

```
mobile/src/main/java/.../simulation/EngineSimulation.kt
mobile/src/main/java/.../tuning/TuningConfig.kt
mobile/src/main/java/.../drive/DriveController.kt
mobile/src/main/java/.../TuningPanel.kt
mobile/src/test/java/.../simulation/EngineSimulationTest.kt
mobile/src/test/java/.../tuning/TuningConfigTest.kt
docs/ui-display-and-simulation-decisions.md
docs/tuning-interface.md
docs/full-implementation.md
docs/llm-handoff.md
```

---

## 8. Critérios de aceite

1. Em **D**, acelerar → RPM sobe conforme curva de potência e `driveMaxRiseRpmPerSec`.
2. Soltar acelerador → RPM cai na taxa de `driveCoastFallRpmPerSec` (±5% em teste unitário) até o idle.
3. Frear → queda adicional via `driveBrakeExtraFallRpmPerSec`.
4. **Live Tuning → VEHICLE → DRIVE RPM** concentra força do RPM + todos os delays.
5. Cada painel tem **DEFAULTS** que restaura só aquela seção.
6. Sliders com títulos em PT-BR explicando o que controlam.
7. Log `lift_off_coast` no DEBUG ao soltar pedal em D.
8. Todos os testes unitários passando.
9. Build debug compila; app roda no emulador/dispositivo.

---

## 9. Fora de escopo / limitações conhecidas

- Expor novamente Seal Performance na UI (permanece oculto; ainda alimenta física EV e gráfico).
- Tornar editáveis as constantes de inércia de N/P (`NEUTRAL_REV_UP/DOWN_RESPONSE_SECONDS`).
- Tornar editável o filtro de aceleração externa BYD (`EXTERNAL_ACCELERATION_FILTER_SECONDS`).
- Sem soft floor, lift-off agressivo pode **reduzir marcha** mais cedo (ex.: 3ª → 2ª) — aceitável; o importante é **não** fazer hunt para cima.

---

## 10. Ordem sugerida de implementação

1. `TuningConfig` + wiring em `DriveController` / `EngineProfile`
2. `integrateDriveModeRpm()` + helpers de potência em `EngineSimulation`
3. Remover soft floor; ajustar `needsEmergencyUpshift()`
4. Testes de simulação (coast fall rate primeiro)
5. `TuningPanel` — DRIVE RPM, delays, DEFAULTS, labels PT-BR
6. Log `lift_off_coast`
7. Atualizar docs
8. Build + run no emulador
