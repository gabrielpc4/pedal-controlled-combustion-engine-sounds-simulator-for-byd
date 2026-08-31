using System.Security.Cryptography;
using System.Text.Json;
using Fmod5Sharp.FmodTypes;
using FModBankParser;
using FModBankParser.Nodes;
using FModBankParser.Nodes.Effects;
using FModBankParser.Nodes.Instruments;
using FModBankParser.Nodes.Transitions;
using FModBankParser.Objects;

namespace FmodBankGraphAudit;

internal static class Program
{
    private const string Schema = "ac-fmod-bank-graph-audit-v3";

    private static int Main(string[] args)
    {
        if (args.Length != 1)
        {
            Console.Error.WriteLine("usage: FmodBankGraphAudit <car-bank-file>");
            return 2;
        }

        var bankPath = Path.GetFullPath(args[0]);
        var reader = FModBankParser.FModBankParser.LoadSoundBank(new FileInfo(bankPath));
        var parserVersion = FModReader.Version;
        var resolved = FModBankParser.FModBankParser.ResolveAudioEvents(reader);

        var instrumentOwners = new HashSet<FModGuid>();
        foreach (var (guid, instrument) in reader.InstrumentNodes)
        {
            instrumentOwners.Add(guid);
            if (instrument.InstrumentBody is { } body)
            {
                instrumentOwners.Add(body.Routable.BaseGuid);
            }
        }

        var events = reader.EventNodes
            .OrderBy(pair => GuidText(pair.Key), StringComparer.Ordinal)
            .Select(pair => EventAudit(reader, resolved, pair.Key, pair.Value))
            .ToArray();

        var controllers = reader.ControllerNodes.Values
            .OrderBy(controller => GuidText(controller.BaseGuid), StringComparer.Ordinal)
            .Select(controller => ControllerAudit(reader, controller))
            .ToArray();

        var payload = new
        {
            schema = Schema,
            bank = new
            {
                fileName = Path.GetFileName(bankPath),
                sha256 = FileSha256(bankPath),
                bankGuid = GuidText(reader.GetBankGuid()),
                fileVersion = parserVersion,
            },
            counts = new
            {
                events = reader.EventNodes.Count,
                instruments = reader.InstrumentNodes.Count,
                waveformResources = reader.WavEntries.Count,
                embeddedSamples = reader.SoundBankData.Sum(soundBank => soundBank.Samples.Count),
                parameters = reader.ParameterNodes.Count,
                controllers = reader.ControllerNodes.Count,
                curves = reader.CurveNodes.Count,
                mappings = reader.MappingNodes.Count,
                modulators = reader.ModulatorNodes.Count,
                timelines = reader.TimelineNodes.Count,
                transitions = reader.TransitionNodes.Count,
                effects = reader.EffectNodes.Count,
                buses = reader.BusNodes.Count,
            },
            coverage = new
            {
                eventsWithCompleteSampleMapping = events.Count(item => item.MappingComplete),
                eventsWithSamples = events.Count(item => item.ResolverSampleIds.Length > 0),
                controllersWithInputParameter = reader.ControllerNodes.Values.Count(
                    controller => reader.ParameterNodes.ContainsKey(controller.InputGuid)
                ),
                controllersWithTimelineInput = reader.ControllerNodes.Values.Count(
                    controller => reader.TimelineNodes.ContainsKey(controller.InputGuid)
                ),
                controllersWithUnknownInput = reader.ControllerNodes.Values.Count(
                    controller =>
                        !reader.ParameterNodes.ContainsKey(controller.InputGuid) &&
                        !reader.TimelineNodes.ContainsKey(controller.InputGuid)
                ),
                controllersWithCurve = reader.ControllerNodes.Values.Count(
                    controller => reader.CurveNodes.ContainsKey(controller.CurveGuid)
                ),
                instrumentOrRouteControllers = reader.ControllerNodes.Values.Count(
                    controller => instrumentOwners.Contains(controller.PropertyOwnerGuid)
                ),
            },
            parameters = reader.ParameterNodes.Values
                .OrderBy(parameter => GuidText(parameter.BaseGuid), StringComparer.Ordinal)
                .Select(parameter => new
                {
                    guid = GuidText(parameter.BaseGuid),
                    parameter.Name,
                    type = parameter.Type.ToString(),
                    minimum = parameter.Minimum,
                    maximum = parameter.Maximum,
                    defaultValue = parameter.DefaultValue,
                    velocity = parameter.Velocity,
                    seekSpeed = parameter.SeekSpeed,
                    seekSpeedDown = parameter.SeekSpeedDown,
                })
                .ToArray(),
            unknownChunks = reader.UnknownChunkCounts
                .OrderBy(pair => pair.Key)
                .Select(pair => new
                {
                    id = pair.Key,
                    hexId = $"0x{unchecked((uint)pair.Key):x8}",
                    count = pair.Value,
                })
                .ToArray(),
            featureKinds = new
            {
                instruments = KindCounts(
                    reader.InstrumentNodes.Values.Select(node => node.GetType().Name)
                ),
                curvePointTypes = reader.CurveNodes.Values
                    .SelectMany(curve => curve.CurvePoints)
                    .GroupBy(point => point.Type)
                    .OrderBy(group => group.Key)
                    .Select(group => new { type = group.Key, count = group.Count() })
                    .ToArray(),
                modulators = KindCounts(
                    reader.ModulatorNodes.Values.Select(node => node.Type.ToString())
                ),
                effectNodes = KindCounts(
                    reader.EffectNodes.Values.Select(node => node.GetType().Name)
                ),
                buses = KindCounts(
                    reader.BusNodes.Values.Select(node => node.GetType().Name)
                ),
                transitions = KindCounts(
                    reader.TransitionNodes.Values.Select(node => node.GetType().Name)
                ),
                builtInDsps = KindCounts(
                    reader.EffectNodes.Values
                        .OfType<BuiltInEffectNode>()
                        .Select(node => node.DSPType.ToString())
                ),
                pluginDsps = KindCounts(
                    reader.EffectNodes.Values
                        .OfType<PluginEffectNode>()
                        .Select(node => $"{node.PluginName}|{node.Name}")
                ),
                controllerInputs = KindCounts(
                    reader.ControllerNodes.Values.Select(controller =>
                        ControllerInputKind(reader, controller)
                    )
                ),
            },
            curves = reader.CurveNodes.Values
                .OrderBy(curve => GuidText(curve.BaseGuid), StringComparer.Ordinal)
                .Select(curve => new
                {
                    guid = GuidText(curve.BaseGuid),
                    ownerGuid = GuidText(curve.OwnerGuid),
                    points = CurvePoints(curve),
                })
                .ToArray(),
            controllers,
            modulators = reader.ModulatorNodes.Values
                .OrderBy(node => GuidText(node.BaseGuid), StringComparer.Ordinal)
                .Select(node => new
                {
                    guid = GuidText(node.BaseGuid),
                    ownerGuid = GuidText(node.OwnerGuid),
                    node.PropertyIndex,
                    type = node.Type.ToString(),
                    typeValue = (int)node.Type,
                    propertyType = node.PropertyType.ToString(),
                    propertyTypeValue = (int)node.PropertyType,
                    clockSource = node.ClockSource.ToString(),
                    subnodeKind = node.Subnode?.GetType().Name,
                    subnodeParsed = node.Subnode is not null,
                })
                .ToArray(),
            effects = reader.EffectNodes
                .OrderBy(pair => GuidText(pair.Key), StringComparer.Ordinal)
                .Select(pair => EffectAudit(pair.Key, pair.Value))
                .ToArray(),
            instruments = reader.InstrumentNodes
                .OrderBy(pair => GuidText(pair.Key), StringComparer.Ordinal)
                .Select(pair => InstrumentAudit(reader, pair.Key, pair.Value))
                .ToArray(),
            events,
        };

        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            WriteIndented = true,
        };
        Console.WriteLine(JsonSerializer.Serialize(payload, options));
        return events.All(item => item.MappingComplete) ? 0 : 3;
    }

    private static EventAuditRecord EventAudit(
        FModReader reader,
        Dictionary<FModGuid, List<FmodSample>> resolved,
        FModGuid eventGuid,
        EventNode eventNode
    )
    {
        var reachable = ReachableInstruments(reader, eventGuid);
        var mappedSamples = reachable
            .Select(guid => reader.InstrumentNodes.TryGetValue(guid, out var node) ? node : null)
            .OfType<WaveformInstrumentNode>()
            .Select(node => TrySample(reader, node.WaveformResourceGuid, out var sample, out _, out _)
                ? SampleId(sample)
                : null)
            .Where(value => value is not null)
            .Cast<string>()
            .Distinct(StringComparer.Ordinal)
            .Order(StringComparer.Ordinal)
            .ToArray();

        var resolverSamples = resolved.TryGetValue(eventGuid, out var eventSamples)
            ? eventSamples.Select(SampleId)
                .Distinct(StringComparer.Ordinal)
                .Order(StringComparer.Ordinal)
                .ToArray()
            : [];

        var timelinePlacements = new List<object>();
        if (reader.TimelineNodes.TryGetValue(eventNode.TimelineGuid, out var timeline))
        {
            timelinePlacements.AddRange(timeline.TriggerBoxes.Select(box => new
            {
                instrumentGuid = GuidText(box.Guid),
                startTime = box.StartTime,
                length = box.Length,
                timeLocked = false,
            }));
            timelinePlacements.AddRange(timeline.TimeLockedTriggerBoxes.Select(box => new
            {
                instrumentGuid = GuidText(box.Guid),
                startTime = box.StartTime,
                length = box.Length,
                timeLocked = true,
            }));
        }

        var parameterPlacements = eventNode.ParameterLayouts
            .Where(reader.ParameterLayoutNodes.ContainsKey)
            .SelectMany(layoutGuid =>
            {
                var layout = reader.ParameterLayoutNodes[layoutGuid];
                var parameterName = reader.ParameterNodes.TryGetValue(layout.ParameterGuid, out var parameter)
                    ? parameter.Name
                    : null;
                return layout.TriggerBoxes.Select(box => new
                {
                    layoutGuid = GuidText(layoutGuid),
                    parameterGuid = GuidText(layout.ParameterGuid),
                    parameterName,
                    instrumentGuid = GuidText(box.InstrumentGuid),
                    start = box.Start,
                    end = box.End,
                    box.IncludeEnd,
                });
            })
            .OrderBy(item => item.parameterGuid, StringComparer.Ordinal)
            .ThenBy(item => item.start)
            .ThenBy(item => item.instrumentGuid, StringComparer.Ordinal)
            .ToArray();

        return new EventAuditRecord(
            GuidText(eventGuid),
            GuidText(eventNode.TimelineGuid),
            eventNode.ParameterLayouts.Select(GuidText).Order(StringComparer.Ordinal).ToArray(),
            timelinePlacements.OrderBy(item => JsonSerializer.Serialize(item), StringComparer.Ordinal).ToArray(),
            parameterPlacements,
            reachable.Select(GuidText).Order(StringComparer.Ordinal).ToArray(),
            mappedSamples,
            resolverSamples,
            mappedSamples.SequenceEqual(resolverSamples, StringComparer.Ordinal)
        );
    }

    private static HashSet<FModGuid> ReachableInstruments(FModReader reader, FModGuid rootEventGuid)
    {
        var instruments = new HashSet<FModGuid>();
        var visited = new HashSet<FModGuid>();
        var stack = new Stack<FModGuid>();
        stack.Push(rootEventGuid);

        var transitionsByDestination = reader.TransitionNodes.Values
            .OfType<TransitionRegionNode>()
            .ToLookup(node => node.DestinationGuid);

        while (stack.TryPop(out var guid))
        {
            if (!visited.Add(guid))
            {
                continue;
            }

            if (reader.EventNodes.TryGetValue(guid, out var eventNode))
            {
                stack.Push(eventNode.TimelineGuid);
                foreach (var layoutGuid in eventNode.ParameterLayouts)
                {
                    stack.Push(layoutGuid);
                }
                foreach (var instrumentGuid in eventNode.EventTriggeredInstruments)
                {
                    stack.Push(instrumentGuid);
                }
            }

            if (reader.ParameterLayoutNodes.TryGetValue(guid, out var layout))
            {
                foreach (var instrumentGuid in layout.Instruments)
                {
                    stack.Push(instrumentGuid);
                }
                foreach (var box in layout.TriggerBoxes)
                {
                    stack.Push(box.InstrumentGuid);
                }
            }

            if (reader.TimelineNodes.TryGetValue(guid, out var timeline))
            {
                foreach (var box in timeline.TriggerBoxes)
                {
                    stack.Push(box.Guid);
                }
                foreach (var box in timeline.TimeLockedTriggerBoxes)
                {
                    stack.Push(box.Guid);
                }
                foreach (var marker in timeline.TimelineNamedMarkers)
                {
                    stack.Push(marker.BaseGuid);
                }
                foreach (var marker in timeline.TimelineTempoMarkers)
                {
                    stack.Push(marker.BaseGuid);
                }
            }

            foreach (var transition in transitionsByDestination[guid])
            {
                if (transition.TransitionBody is not { } body)
                {
                    continue;
                }
                foreach (var box in body.TriggeredTriggerBoxes)
                {
                    stack.Push(box.Guid);
                }
                foreach (var box in body.TimeLockedTriggerBoxes)
                {
                    stack.Push(box.Guid);
                }
            }

            if (!reader.InstrumentNodes.TryGetValue(guid, out var instrument))
            {
                continue;
            }

            instruments.Add(guid);
            if (instrument.InstrumentBody is { } instrumentBody)
            {
                stack.Push(instrumentBody.TimelineGuid);
            }
            if (instrument is MultiInstrumentNode { PlaylistBody: { } multiPlaylist })
            {
                foreach (var entry in multiPlaylist.Entries)
                {
                    stack.Push(entry.Guid);
                }
            }
            else if (instrument is ScattererInstrumentNode { PlaylistBody: { } scatterPlaylist })
            {
                foreach (var entry in scatterPlaylist.Entries)
                {
                    stack.Push(entry.Guid);
                }
            }
            else if (instrument is EventInstrumentNode eventInstrument)
            {
                stack.Push(eventInstrument.EventGuid);
            }
        }

        return instruments;
    }

    private static object InstrumentAudit(
        FModReader reader,
        FModGuid instrumentGuid,
        BaseInstrumentNode instrument
    )
    {
        var body = instrument.InstrumentBody;
        var children = instrument switch
        {
            MultiInstrumentNode { PlaylistBody: { } childPlaylist } => childPlaylist.Entries,
            ScattererInstrumentNode { PlaylistBody: { } childPlaylist } => childPlaylist.Entries,
            _ => [],
        };
        var playlist = instrument switch
        {
            MultiInstrumentNode { PlaylistBody: { } value } => value,
            ScattererInstrumentNode { PlaylistBody: { } value } => value,
            _ => null,
        };
        var controllerOwnerGuids = new HashSet<FModGuid> { instrumentGuid };
        if (body is not null)
        {
            controllerOwnerGuids.Add(body.Routable.BaseGuid);
        }

        object? sample = null;
        if (instrument is WaveformInstrumentNode waveform &&
            TrySample(reader, waveform.WaveformResourceGuid, out var rawSample, out var bankIndex, out var sampleIndex))
        {
            sample = new
            {
                waveformResourceGuid = GuidText(waveform.WaveformResourceGuid),
                soundBankIndex = bankIndex,
                subsoundIndex = sampleIndex,
                name = SampleName(rawSample, bankIndex, sampleIndex),
                encodedPayloadSha256 = BytesSha256(rawSample.SampleBytes),
                encodedPayloadBytes = rawSample.SampleBytes.Length,
                frequencyHz = rawSample.Metadata.Frequency,
                channels = rawSample.Metadata.Channels,
                sampleCount = rawSample.Metadata.SampleCount,
            };
        }

        return new
        {
            guid = GuidText(instrumentGuid),
            kind = instrument.GetType().Name,
            sample,
            // PlaylistBody.Entries is the authored scheduler order.  Sorting
            // by GUID here would turn PlaySequential into a different program
            // for every consumer of the graph audit.
            childInstruments = children
                .Select((entry, authoredOrder) => new
                {
                    guid = GuidText(entry.Guid),
                    entry.Weight,
                    authoredOrder,
                })
                .ToArray(),
            playlist = playlist is null ? null : new
            {
                playMode = playlist.PlayMode.ToString(),
                playModeValue = (int)playlist.PlayMode,
                selectionMode = playlist.SelectionMode.ToString(),
                selectionModeValue = (int)playlist.SelectionMode,
            },
            eventGuid = instrument is EventInstrumentNode eventInstrument
                ? GuidText(eventInstrument.EventGuid)
                : null,
            baseProperties = body is null ? null : new
            {
                volumeDb = body.Volume,
                volumeRawUInt32 = BitConverter.SingleToUInt32Bits(body.Volume),
                volumeFileOffset = body.VolumeFileOffset,
                pitchSemitones = body.Pitch,
                body.LoopCount,
                body.AutoPitchReference,
                body.AutoPitchAtMinimum,
                body.InitialSeekPosition,
                body.InitialSeekPercent,
                timelineGuid = GuidText(body.TimelineGuid),
                routableGuid = GuidText(body.Routable.BaseGuid),
                body.TriggerChancePercent,
                triggerChancePercentRawUInt32 = BitConverter.SingleToUInt32Bits(
                    body.TriggerChancePercent
                ),
                triggerChancePercentFileOffset = body.TriggerChancePercentFileOffset,
            },
            controllerGuids = reader.ControllerNodes.Values
                .Where(controller => controllerOwnerGuids.Contains(controller.PropertyOwnerGuid))
                .Select(controller => GuidText(controller.BaseGuid))
                .Order(StringComparer.Ordinal)
                .ToArray(),
        };
    }

    private static object ControllerAudit(FModReader reader, ControllerNode controller)
    {
        reader.ParameterNodes.TryGetValue(controller.InputGuid, out var parameter);
        reader.CurveNodes.TryGetValue(controller.CurveGuid, out var curve);
        return new
        {
            guid = GuidText(controller.BaseGuid),
            propertyOwnerGuid = GuidText(controller.PropertyOwnerGuid),
            inputKind = ControllerInputKind(reader, controller),
            inputParameterGuid = GuidText(controller.InputGuid),
            inputParameterName = parameter?.Name,
            controller.PropertyIndex,
            curveGuid = GuidText(controller.CurveGuid),
            points = curve is null ? [] : CurvePoints(curve),
        };
    }

    private static string ControllerInputKind(FModReader reader, ControllerNode controller) =>
        reader.ParameterNodes.ContainsKey(controller.InputGuid)
            ? "parameter"
            : reader.TimelineNodes.ContainsKey(controller.InputGuid)
                ? "timeline"
                : "unknownGuid";

    private static object EffectAudit(FModGuid effectGuid, BaseEffectNode effect)
    {
        var parameterized = effect switch
        {
            BuiltInEffectNode builtIn => builtIn.ParamEffectBody,
            PluginEffectNode plugin => plugin.ParamEffectBody,
            _ => null,
        };
        return new
        {
            guid = GuidText(effectGuid),
            kind = effect.GetType().Name,
            builtInDsp = effect is BuiltInEffectNode builtInEffect
                ? builtInEffect.DSPType.ToString()
                : null,
            builtInDspValue = effect is BuiltInEffectNode builtInEffectValue
                ? (uint?)builtInEffectValue.DSPType
                : null,
            pluginName = (effect as PluginEffectNode)?.PluginName,
            pluginEffectName = (effect as PluginEffectNode)?.Name,
            hasEffectBody = effect.EffectBody is not null,
            parameterTypes = parameterized?.Parameters
                .GroupBy(parameter => parameter.Type)
                .OrderBy(group => group.Key)
                .Select(group => new { type = group.Key, count = group.Count() })
                .ToArray() ?? [],
        };
    }

    private static object[] CurvePoints(CurveNode curve) => curve.CurvePoints
        .Select(point => (object)new
        {
            x = point.X,
            xRawUInt32 = BitConverter.SingleToUInt32Bits(point.X),
            y = point.Y,
            shape = point.Shape,
            type = point.Type,
        })
        .ToArray();

    private static object[] KindCounts(IEnumerable<string> kinds) => kinds
        .GroupBy(value => value, StringComparer.Ordinal)
        .OrderBy(group => group.Key, StringComparer.Ordinal)
        .Select(group => (object)new { kind = group.Key, count = group.Count() })
        .ToArray();

    private static bool TrySample(
        FModReader reader,
        FModGuid waveformResourceGuid,
        out FmodSample sample,
        out int bankIndex,
        out int sampleIndex
    )
    {
        sample = null!;
        bankIndex = -1;
        sampleIndex = -1;
        if (!reader.WavEntries.TryGetValue(waveformResourceGuid, out var entry))
        {
            return false;
        }
        bankIndex = entry.SoundBankIndex;
        sampleIndex = entry.SubsoundIndex;
        if (bankIndex < 0 || bankIndex >= reader.SoundBankData.Count ||
            sampleIndex < 0 || sampleIndex >= reader.SoundBankData[bankIndex].Samples.Count)
        {
            return false;
        }
        sample = reader.SoundBankData[bankIndex].Samples[sampleIndex];
        return true;
    }

    private static string SampleId(FmodSample sample) =>
        $"{sample.Name ?? "?"}|{BytesSha256(sample.SampleBytes)}";

    private static string SampleName(FmodSample sample, int bankIndex, int sampleIndex) =>
        string.IsNullOrWhiteSpace(sample.Name)
            ? $"sample-{bankIndex}-{sampleIndex}"
            : sample.Name;

    private static string GuidText(FModGuid guid) => guid.ToString().ToLowerInvariant();

    private static string BytesSha256(byte[] bytes) =>
        Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    private static string FileSha256(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    private sealed record EventAuditRecord(
        string Guid,
        string TimelineGuid,
        string[] ParameterLayoutGuids,
        object[] TimelinePlacements,
        object[] ParameterPlacements,
        string[] ReachableInstrumentGuids,
        string[] MappedSampleIds,
        string[] ResolverSampleIds,
        bool MappingComplete
    );
}
