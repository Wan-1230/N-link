//
//  CameraParameterManager.swift
//  N-Link
//
//  曝光参数读写与轮询。对应安卓 `camera/params/CameraParameterManager.kt`（2078 行）。
//
//  三条来自真机的纪律（照抄安卓注释）：
//   1. `0x500E` 写回 OK **不等于**真的切了档——机身会在某些状态静默忽略，
//      必须回读断言，连续失败就把「本会话不支持切档」记下来，UI 转成只读。
//   2. 读参数前先清空机身名/固件/序列号——换一台机身时不能残留上一台的型号。
//   3. AP 推流时把模式轮询拉长到 ≥1500ms。真机实测 AP 3.7s 才能回一次，
//      STA 0.3s 就够；按 500ms 硬轮询只会把命令通道占满、把监看帧挤掉。
//

import Foundation

// MARK: - 参数管理器

@MainActor
final class CameraParameterManager: ObservableObject {

    private enum C {
        static let pollInterval: TimeInterval = 2.0
        static let modeWatchInterval: TimeInterval = 0.5
        static let streamingModeWatchFloor: TimeInterval = 1.5
        /// 0x1014 枚举上限。超过就是解析错位，不是真的有 64 档。
        static let maxEnumCount = 64
        static let modeConfirmAttempts = 4
        static let modeConfirmIntervalMs = 400
    }

    // MARK: 对外状态（对齐安卓同名 StateFlow）

    @Published private(set) var aperture: CameraParam = .empty
    @Published private(set) var shutterSpeed: CameraParam = .empty
    @Published private(set) var iso: CameraParam = .empty
    @Published private(set) var evCompensation: CameraParam = .empty
    @Published private(set) var whiteBalance: CameraParam = .empty
    @Published private(set) var focusMode: CameraParam = .empty
    @Published private(set) var exposureProgram: CameraParam = .empty
    @Published private(set) var meteringMode: CameraParam = .empty
    @Published private(set) var cameraInfo: CameraInfo = CameraInfo()
    @Published private(set) var colorTemperature: Int = 5500
    /// 用户手动锁参（设置页开关）。锁住时所有写操作直接返回 false。
    @Published var paramsLocked: Bool = false
    /// 本会话确认无法切档（0x500E 写不进）。UI 应把模式格转成只读并说明原因。
    @Published private(set) var modeSwitchUnsupported: Bool = false

    // MARK: 依赖

    private let client: CameraConnectionClient
    private let log: @Sendable (String) -> Void
    private var pollTask: Task<Void, Never>?
    private var modeWatchTask: Task<Void, Never>?
    private var refreshPending = false
    private var exposureChoices: [Int] = []

    init(client: CameraConnectionClient, log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.client = client
        self.log = log
    }

    // MARK: - 读取

    /// 读一轮全部参数。
    ///
    /// 顺序与安卓一致：先清易变字段，再逐个读。任一参数读失败只跳过该项，
    /// 不中断整轮——机身在某些档位下个别属性会短暂不可读。
    func readAll() async {
        guard let session = client.sessionOrNil() else {
            clearConnectionScopedFields()
            return
        }
        // 换机身不残留：型号/固件/序列号每轮都从 DeviceInfo 重取。
        cameraInfo.modelName = session.deviceInfo?.model ?? ""
        cameraInfo.firmwareVersion = session.deviceInfo?.deviceVersion ?? ""
        cameraInfo.serialNumber = session.deviceInfo?.serialNumber ?? ""
        modeSwitchUnsupported = false

        await readExposureProgram(session)
        await readAperture(session)
        await readShutterSpeed(session)
        await readIso(session)
        await readEv(session)
        await readWhiteBalance(session)
        await readFocusMode(session)
        await readMetering(session)
        await readBattery(session)
        await readLens(session)
        await readStorage(session)
        await loadExposureChoices(session)
    }

    private func clearConnectionScopedFields() {
        cameraInfo = CameraInfo()
        aperture = .empty
        shutterSpeed = .empty
        iso = .empty
        evCompensation = .empty
        whiteBalance = .empty
        focusMode = .empty
        exposureProgram = .empty
        meteringMode = .empty
    }

    private func readExposureProgram(_ session: PtpSession) async {
        guard let raw = await readUInt16(session, Ptp.propExposureProgramMode) else { return }
        exposureProgram = CameraParam(
            currentValue: Self.describeExposureProgram(Int(raw)),
            availableValues: exposureChoices.map { Self.describeExposureProgram($0) },
            availableRaw: exposureChoices,
            rawValue: Int(raw)
        )
    }

    private func readAperture(_ session: PtpSession) async {
        guard let raw = await readUInt16(session, Ptp.propFNumber) else { return }
        let fX100 = Int(raw)
        var options = ApertureCatalog.fallback
        if let choices = await readEnumValues(session, Ptp.propFNumber), choices.count > 1 {
            options = choices.map(Int.init)
        }
        aperture = CameraParam(
            currentValue: ApertureCatalog.format(fX100),
            availableValues: options.map(ApertureCatalog.format),
            availableRaw: options,
            rawValue: fX100
        )
    }

    /// 快门：同时读 0x500D（1/10000s 定点）与 0xD100（分子/分母打包），
    /// 尼康那个优先——它才有 1/3 档的精确值。
    private func readShutterSpeed(_ session: PtpSession) async {
        var seconds: Double?
        var catalogRaw = 0

        if let nikonRaw = await readUInt32(session, Ptp.propNikonShutterSpeed),
           let parsed = ShutterCatalog.parseNikonShutterSpeed(nikonRaw) {
            seconds = parsed
        }

        let stdRaw = await readUInt32(session, Ptp.propExposureTime)
        if seconds == nil, let stdRaw, stdRaw != 0xFFFF_FFFF {
            let asInt = Int(stdRaw)
            if ShutterCatalog.isValidExposureTime(asInt) {
                seconds = Double(asInt) / 10000.0
            }
        }

        if let seconds {
            catalogRaw = ShutterCatalog.matchBySeconds(seconds)
        } else if stdRaw == 0xFFFF_FFFF || stdRaw == nil {
            // 0xFFFFFFFF 就是 B 门。读不到也按 B 门呈现——比显示「--」更接近真相。
            shutterSpeed = CameraParam(
                currentValue: "B门",
                availableValues: ShutterCatalog.stops.map(\.label) + ["B门"],
                availableRaw: ShutterCatalog.stops.map(\.raw) + [0],
                rawValue: 0
            )
            return
        }

        shutterSpeed = CameraParam(
            currentValue: ShutterCatalog.format(rawX10000: catalogRaw),
            availableValues: ShutterCatalog.stops.map(\.label),
            availableRaw: ShutterCatalog.stops.map(\.raw),
            rawValue: catalogRaw
        )
    }

    private func readIso(_ session: PtpSession) async {
        guard let raw = await readUInt32(session, Ptp.propExposureIndex) else { return }
        let value = Int(raw)
        let options = Self.commonIsoValues + [IsoCatalog.autoRaw, IsoCatalog.lowRaw]
        iso = CameraParam(
            currentValue: IsoCatalog.format(value),
            availableValues: options.map(IsoCatalog.format),
            availableRaw: options,
            rawValue: value
        )
    }

    /// EV 是 1/6 EV 定点数，显示保留一位（`+0.3` / `-1.7`）。
    private func readEv(_ session: PtpSession) async {
        guard let raw = await readInt16(session, Ptp.propExposureBiasCompensation) else { return }
        let sixth = Int(raw)
        let steps = Array(stride(from: -30, through: 30, by: 3))
        evCompensation = CameraParam(
            currentValue: String(format: "%+.1f", Double(sixth) / 6.0),
            availableValues: steps.map { String(format: "%+.1f", Double($0) / 6.0) },
            availableRaw: steps,
            rawValue: sixth
        )
    }

    private func readWhiteBalance(_ session: PtpSession) async {
        guard let raw = await readUInt16(session, Ptp.propWhiteBalance) else { return }
        let value = Int(raw)
        let order: [Int] = [2, 0x8016, 4, 0x8010, 0x8011, 6, 5, 7, 0x8012, 0x8013]
        let names: [Int: String] = [
            2: "自动", 0x8016: "自然光自动适应", 4: "晴天", 0x8010: "阴天", 0x8011: "背阴",
            6: "白炽灯", 5: "荧光灯", 7: "闪光灯", 0x8012: "选择色温", 0x8013: "手动预设"
        ]
        let label = names[value] ?? "未知(\(value))"
        whiteBalance = CameraParam(
            currentValue: label,
            availableValues: order.compactMap { names[$0] },
            availableRaw: order,
            rawValue: value
        )
    }

    private func readFocusMode(_ session: PtpSession) async {
        guard let raw = await readUInt16(session, Ptp.propFocusMode) else { return }
        let value = Int(raw)
        let order: [Int] = [1, 0x8010, 0x8011, 0x8012, 0x8013]
        let names: [Int: String] = [
            1: "MF (手动)", 0x8010: "AF-S (单次)", 0x8011: "AF-C (连续)",
            0x8012: "AF-A (自动切换)", 0x8013: "AF-F (全时)"
        ]
        focusMode = CameraParam(
            currentValue: names[value] ?? "未知(\(value))",
            availableValues: order.compactMap { names[$0] },
            availableRaw: order,
            rawValue: value
        )
    }

    private func readMetering(_ session: PtpSession) async {
        guard let raw = await readUInt16(session, Ptp.propExposureMeteringMode) else { return }
        let value = Int(raw)
        let order: [Int] = [3, 2, 4, 0x8010]
        let names: [Int: String] = [2: "中央重点", 3: "矩阵测光", 4: "点测光", 0x8010: "高光重点"]
        meteringMode = CameraParam(
            currentValue: names[value] ?? "未知(\(value))",
            availableValues: order.compactMap { names[$0] },
            availableRaw: order,
            rawValue: value
        )
    }

    private func readBattery(_ session: PtpSession) async {
        guard let data = await readRaw(session, Ptp.propBatteryLevel), let first = data.first else { return }
        cameraInfo.batteryLevel = Int(first)
    }

    private func readLens(_ session: PtpSession) async {
        if let lensId = await readUInt32(session, Ptp.propNikonLensId), lensId != 0 {
            cameraInfo.lensName = Self.lensName(fromId: lensId) ?? "未知镜头"
        }
        if let minFocal = await readUInt32(session, Ptp.propNikonFocalLengthMin) {
            cameraInfo.lensFocalMinMm = Double(minFocal) / 100.0
        }
        if let maxFocal = await readUInt32(session, Ptp.propNikonFocalLengthMax) {
            cameraInfo.lensFocalMaxMm = Double(maxFocal) / 100.0
        }
        if let minAp = await readUInt16(session, Ptp.propNikonMaxApAtMin) {
            cameraInfo.lensMaxApAtMinFocalX100 = Int(minAp)
        }
        if let maxAp = await readUInt16(session, Ptp.propNikonMaxApAtMax) {
            cameraInfo.lensMaxApAtMaxFocalX100 = Int(maxAp)
        }
        cameraInfo.lensApertureRangeKnown = cameraInfo.lensMaxApAtMinFocalX100 > 0
    }

    private func readStorage(_ session: PtpSession) async {
        do {
            let storages = try await session.listStorages()
            guard let first = storages.first else { return }
            cameraInfo.storageTotalMb = Int64(first.maxCapacity / 1_048_576)
            cameraInfo.storageFreeMb = Int64(first.freeSpace / 1_048_576)
            cameraInfo.storageDescription = first.volumeLabel.isEmpty ? first.description : first.volumeLabel
        } catch {
            log("读存储信息失败：\(error)")
        }
    }

    /// 0x1014 拿枚举档位。解析失败就静默退回内置表——机身不支持枚举不是错误。
    private func loadExposureChoices(_ session: PtpSession) async {
        guard exposureChoices.isEmpty,
              let choices = await readEnumValues(session, Ptp.propExposureProgramMode),
              choices.count > 1 else { return }
        exposureChoices = choices.map(Int.init)
        exposureProgram = CameraParam(
            currentValue: exposureProgram.currentValue,
            availableValues: exposureChoices.map { Self.describeExposureProgram($0) },
            availableRaw: exposureChoices,
            rawValue: exposureProgram.rawValue
        )
    }

    // MARK: - 快门次数

    /// 查询机身快门次数。
    ///
    /// 尼康**没有**快门计数的 PTP 属性（佳能有 0xD1AC、奥林巴斯 0xD059、富士 0xD154），
    /// 唯一来源是照片 EXIF MakerNote 的 0x00A7。因此这个查询要先从卡上挑 3 张样本，
    /// 再逐张读文件头解 MakerNote。
    ///
    /// 与安卓一致的三条口径：
    /// 1. 只有「一次都没读到字节」才整份重下；读到字节但解不出就跳过，不浪费带宽。
    /// 2. 有多样本时用 `0x00A5 + 0x00A6 == 0x00A7` 恒等式交叉校验。
    /// 3. 样本不一致时报区间，不挑一个数装精确。
    func queryShutterCount() async {
        guard client.isConnected else {
            cameraInfo.shutterQueryState = .failed
            cameraInfo.shutterFailReason = .noMedia
            return
        }
        guard cameraInfo.shutterQueryState != .querying else { return }
        cameraInfo.shutterQueryState = .querying
        cameraInfo.shutterFailReason = .none

        defer { cameraInfo.shutterQueryState = cameraInfo.shutterCount >= 0 ? .success : .failed }

        guard let session = client.sessionOrNil() else { return }
        let loader = AlbumLoader(session: session)
        guard let files = try? await loader.load(holdPages: true, onPhase: { _ in }, onPage: { _ in }) else {
            cameraInfo.shutterFailReason = .readFailed
            return
        }

        let samples = NikonShutterCountParser.pickSamples(files)
        guard !samples.isEmpty else {
            cameraInfo.shutterFailReason = .noMedia
            return
        }

        var counts: [Int] = []
        var verifiedAll = true
        var mechanical = -1

        for sample in samples {
            guard let head = try? await session.readRange(
                handle: sample.handle,
                offset: 0,
                length: UInt32(NikonShutterCountParser.exifHeadBytes)
            ), !head.isEmpty else {
                cameraInfo.shutterFailReason = .readFailed
                continue
            }
            guard let parsed = NikonShutterCountParser.parse(head) else {
                cameraInfo.shutterFailReason = .parseFailed
                verifiedAll = false
                continue
            }
            counts.append(parsed.count)
            if !parsed.verified { verifiedAll = false }
            if parsed.mechanical > mechanical { mechanical = parsed.mechanical }
        }

        guard let maxCount = counts.max() else {
            if cameraInfo.shutterFailReason == .none {
                cameraInfo.shutterFailReason = .parseFailed
            }
            return
        }

        cameraInfo.shutterCount = maxCount
        cameraInfo.shutterCountSource = .local
        cameraInfo.shutterMechanicalCount = mechanical
        cameraInfo.shutterVerified = verifiedAll && (counts.count <= 1 || counts.min() == maxCount)
        if let minCount = counts.min(), counts.count > 1, minCount != maxCount {
            cameraInfo.shutterCountRange = minCount...maxCount
        } else {
            cameraInfo.shutterCountRange = nil
        }
        cameraInfo.shutterQueryState = .success
        cameraInfo.shutterFailReason = .none
    }

    // MARK: - 写入

    func setExposureProgram(_ raw: Int) async -> Bool {
        guard !paramsLocked else { return false }
        guard let session = client.sessionOrNil() else { return false }
        guard await writeUInt16(session, Ptp.propExposureProgramMode, UInt16(raw)) else { return false }
        // 回 OK 不等于切了档：必须回读断言。
        if await confirmExposureProgram(raw) {
            exposureProgram = CameraParam(
                currentValue: Self.describeExposureProgram(Int(raw)),
                availableValues: exposureProgram.availableValues,
                availableRaw: exposureProgram.availableRaw,
                rawValue: raw
            )
            return true
        }
        markModeSwitchUnsupported()
        return false
    }

    func setShutterSpeed(rawX10000: Int) async -> Bool {
        guard !paramsLocked, let session = client.sessionOrNil() else { return false }
        // 优先写 0xD100（精确档位），失败再退回 0x500D。
        if let packed = ShutterCatalog.packedNikonShutterSpeed(rawX10000),
           await writeUInt32(session, Ptp.propNikonShutterSpeed, packed) {
            shutterSpeed = CameraParam(currentValue: ShutterCatalog.format(rawX10000: rawX10000),
                                       availableValues: shutterSpeed.availableValues,
                                       availableRaw: shutterSpeed.availableRaw,
                                       rawValue: rawX10000)
            return true
        }
        guard await writeUInt32(session, Ptp.propExposureTime, UInt32(rawX10000)) else { return false }
        shutterSpeed = CameraParam(currentValue: ShutterCatalog.format(rawX10000: rawX10000),
                                   availableValues: shutterSpeed.availableValues,
                                   availableRaw: shutterSpeed.availableRaw,
                                   rawValue: rawX10000)
        return true
    }

    func setAperture(fX100: Int) async -> Bool {
        guard !paramsLocked, let session = client.sessionOrNil() else { return false }
        guard await writeUInt16(session, Ptp.propFNumber, UInt16(clamping: fX100)) else { return false }
        aperture = CameraParam(currentValue: ApertureCatalog.format(fX100),
                               availableValues: aperture.availableValues,
                               availableRaw: aperture.availableRaw,
                               rawValue: fX100)
        return true
    }

    func setIso(_ raw: Int) async -> Bool {
        guard !paramsLocked, let session = client.sessionOrNil() else { return false }
        guard await writeUInt32(session, Ptp.propExposureIndex, UInt32(clamping: raw)) else { return false }
        iso = CameraParam(currentValue: IsoCatalog.format(raw),
                          availableValues: iso.availableValues,
                          availableRaw: iso.availableRaw,
                          rawValue: raw)
        return true
    }

    func setWhiteBalance(_ raw: Int) async -> Bool {
        guard !paramsLocked, let session = client.sessionOrNil() else { return false }
        guard await writeUInt16(session, Ptp.propWhiteBalance, UInt16(clamping: raw)) else { return false }
        await readWhiteBalance(session)
        return true
    }

    func setFocusMode(_ raw: Int) async -> Bool {
        guard !paramsLocked, let session = client.sessionOrNil() else { return false }
        guard await writeUInt16(session, Ptp.propFocusMode, UInt16(clamping: raw)) else { return false }
        await readFocusMode(session)
        return true
    }

    func setMeteringMode(_ raw: Int) async -> Bool {
        guard !paramsLocked, let session = client.sessionOrNil() else { return false }
        guard await writeUInt16(session, Ptp.propExposureMeteringMode, UInt16(clamping: raw)) else { return false }
        await readMetering(session)
        return true
    }

    func setEv(_ sixthSteps: Int) async -> Bool {
        guard !paramsLocked, let session = client.sessionOrNil() else { return false }
        guard await writeInt16(session, Ptp.propExposureBiasCompensation, Int16(clamping: sixthSteps)) else { return false }
        evCompensation = CameraParam(currentValue: String(format: "%+.1f", Double(sixthSteps) / 6.0),
                                     availableValues: evCompensation.availableValues,
                                     availableRaw: evCompensation.availableRaw,
                                     rawValue: sixthSteps)
        return true
    }

    // MARK: - 按显示名写入（UI 层只拿得到 label）

    /// 把 UI 选中的显示名翻回协议原始值再写入。
    ///
    /// UI 只持有显示名（`f/2.8`、`1/250`、`AF-C (连续)`），写回必须走参数里
    /// 那份 `availableValues`/`availableRaw` 平行表。**不做任何字符串反解析**：
    /// 快门有 `1/2.5` 这种分数、测光有「未知(n)」，反解析一定会翻车。
    private func rawValue(for label: String, in param: CameraParam) -> Int? {
        guard let index = param.availableValues.firstIndex(of: label),
              param.availableRaw.indices.contains(index) else { return nil }
        return param.availableRaw[index]
    }

    func setExposureProgram(label: String) async -> Bool {
        guard let raw = rawValue(for: label, in: exposureProgram) else { return false }
        return await setExposureProgram(raw)
    }

    func setAperture(label: String) async -> Bool {
        guard let raw = rawValue(for: label, in: aperture) else { return false }
        return await setAperture(fX100: raw)
    }

    func setShutterSpeed(label: String) async -> Bool {
        // 「B门」对应 raw 0，不能当普通档位写 0x500D=0。
        if label == "B门" { return false }
        guard let raw = rawValue(for: label, in: shutterSpeed) else { return false }
        return await setShutterSpeed(rawX10000: raw)
    }

    func setIso(label: String) async -> Bool {
        guard let raw = rawValue(for: label, in: iso) else { return false }
        return await setIso(raw)
    }

    func setWhiteBalance(label: String) async -> Bool {
        guard let raw = rawValue(for: label, in: whiteBalance) else { return false }
        return await setWhiteBalance(raw)
    }

    func setFocusMode(label: String) async -> Bool {
        guard let raw = rawValue(for: label, in: focusMode) else { return false }
        return await setFocusMode(raw)
    }

    func setMeteringMode(label: String) async -> Bool {
        guard let raw = rawValue(for: label, in: meteringMode) else { return false }
        return await setMeteringMode(raw)
    }

    /// 套用一条预设。逐项写、单项失败不中断，最后汇总。
    ///
    /// 返回失败项的名字列表；空数组表示全部写成功。刻意不做「全部回滚」——
    /// 相机参数没有事务，回滚只会把已经成功的项再改回去，用户看到的更乱。
    @discardableResult
    func applyPreset(_ preset: ShootingPreset, lookups: PresetLookup? = nil) async -> [String] {
        guard !paramsLocked else { return ["参数已锁定"] }
        var failed: [String] = []

        if await setAperture(fX100: preset.aperture) == false { failed.append("光圈") }
        if await setShutterSpeed(rawX10000: preset.shutterSpeed) == false { failed.append("快门") }
        if await setIso(preset.iso) == false { failed.append("ISO") }
        if await setWhiteBalance(preset.whiteBalance) == false { failed.append("白平衡") }
        if await setFocusMode(preset.focusMode) == false { failed.append("对焦模式") }
        if await setMeteringMode(preset.meteringMode) == false { failed.append("测光") }

        if !failed.isEmpty {
            log("预设「\(preset.name)」部分写入失败：\(failed.joined(separator: "、"))")
        }
        return failed
    }

    /// 预设写入时需要的可选查表（当前未使用，保留给以后按机身能力裁剪预设项）。
    struct PresetLookup: Sendable {
        var apertureChoices: [Int] = []
        var shutterChoices: [Int] = []
    }

    /// 写进机身并回读确认档位真的变了。
    private func confirmExposureProgram(_ target: Int) async -> Bool {
        guard let session = client.sessionOrNil() else { return false }
        for _ in 0..<C.modeConfirmAttempts {
            try? await Clock.sleep(ms: C.modeConfirmIntervalMs)
            if let current = await readUInt16(session, Ptp.propExposureProgramMode), Int(current) == target {
                return true
            }
        }
        log("0x500E 写 \(target) 回读不一致")
        return false
    }

    private func markModeSwitchUnsupported() {
        modeSwitchUnsupported = true
        log("本会话判定为不支持切档")
    }

    // MARK: - 轮询

    func startPolling(intervalMs: TimeInterval? = nil) {
        stopPolling()
        let interval = intervalMs ?? C.pollInterval
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Clock.sleep(seconds: interval)
                guard let self, !Task.isCancelled else { return }
                await self.readAll()
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// 模式单属性快轮询。推流时自动拉长到 ≥1.5s（见文件头第 3 条）。
    func startModeWatch(streaming: Bool) {
        modeWatchTask?.cancel()
        let interval = streaming
            ? max(C.modeWatchInterval, C.streamingModeWatchFloor)
            : C.modeWatchInterval
        modeWatchTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Clock.sleep(seconds: interval)
                guard let self, !Task.isCancelled else { return }
                await self.refreshExposureProgramOnly()
            }
        }
    }

    func stopModeWatch() {
        modeWatchTask?.cancel()
        modeWatchTask = nil
    }

    private func refreshExposureProgramOnly() async {
        guard let session = client.sessionOrNil(),
              let raw = await readUInt16(session, Ptp.propExposureProgramMode) else { return }
        let value = Int(raw)
        guard value != exposureProgram.rawValue else { return }
        exposureProgram = CameraParam(
            currentValue: Self.describeExposureProgram(value),
            availableValues: exposureProgram.availableValues,
            rawValue: value
        )
    }

    /// 事件驱动的合并刷新：相机一次连发多轮 0x4006 时只读一次。
    func refreshFromDeviceEvent() {
        guard !refreshPending else { return }
        refreshPending = true
        Task { [weak self] in
            try? await Clock.sleep(ms: 120)
            guard let self else { return }
            self.refreshPending = false
            await self.readAll()
        }
    }

    func refreshCameraInfoNow() async {
        await readAll()
    }

    // MARK: - 底层读写

    private func readRaw(_ session: PtpSession, _ code: UInt16) async -> Data? {
        do {
            let r = try await session.getDevicePropValue(code)
            guard r.isOk, let data = r.dataIn, !data.isEmpty else { return nil }
            return data
        } catch {
            return nil
        }
    }

    private func readUInt16(_ session: PtpSession, _ code: UInt16) async -> UInt16? {
        guard let data = await readRaw(session, code), data.count >= 2 else { return nil }
        return data.readLE16(at: 0)
    }

    private func readInt16(_ session: PtpSession, _ code: UInt16) async -> Int16? {
        await readUInt16(session, code).map { Int16(bitPattern: $0) }
    }

    private func readUInt32(_ session: PtpSession, _ code: UInt16) async -> UInt32? {
        guard let data = await readRaw(session, code), data.count >= 4 else { return nil }
        return data.readLE32(at: 0)
    }

    private func writeUInt16(_ session: PtpSession, _ code: UInt16, _ value: UInt16) async -> Bool {
        var payload = Data()
        payload.appendLE(value)
        return await write(session, code, payload)
    }

    private func writeInt16(_ session: PtpSession, _ code: UInt16, _ value: Int16) async -> Bool {
        var payload = Data()
        payload.appendLE(UInt16(bitPattern: value))
        return await write(session, code, payload)
    }

    private func writeUInt32(_ session: PtpSession, _ code: UInt16, _ value: UInt32) async -> Bool {
        var payload = Data()
        payload.appendLE(value)
        return await write(session, code, payload)
    }

    private func write(_ session: PtpSession, _ code: UInt16, _ payload: Data) async -> Bool {
        do {
            let r = try await session.setDevicePropValue(code, value: payload)
            return r.isOk
        } catch {
            log("写属性 \(String(format: "0x%04X", code)) 失败：\(error)")
            return false
        }
    }

    /// 读 0x1014 的枚举值列表。
    ///
    /// PTP 1.1 §5.1.5 的 DevicePropDesc 布局：code u16 / dataType u16 / getSet u8 /
    /// defaultValue / currentValue / formFlag u8。只有 formFlag == 2（Enumeration）
    /// 才跟 u16 count + count×u16，其余形态（Range=1 / None=0）都没有档位表。
    private func readEnumValues(_ session: PtpSession, _ code: UInt16) async -> [UInt16]? {
        do {
            let r = try await session.rawTransact(opcode: Ptp.opGetDevicePropDesc, params: [UInt32(code)])
            guard r.isOk, let data = r.dataIn else { return nil }
            var reader = PtpDatasetReader(data)
            guard let _ = try? reader.u16(),          // code
                  let dataType = try? reader.u16(),   // dataType
                  let _ = try? reader.u8() else { return nil } // getSet
            let width = max(1, Self.dataTypeWidth(dataType))
            guard (try? reader.skip(width * 2)) != nil else { return nil } // default + current
            guard let formFlag = try? reader.u8() else { return nil }
            guard formFlag == 2, let count = try? reader.u16(),
                  count > 1, Int(count) <= C.maxEnumCount else { return nil }
            var values: [UInt16] = []
            for _ in 0..<count {
                guard let v = try? reader.u16() else { return nil }
                values.append(v)
            }
            return values
        } catch {
            return nil
        }
    }

    private static func dataTypeWidth(_ type: UInt16) -> Int {
        switch type {
        case 1, 2: return 1
        case 3, 4: return 2
        case 5, 6: return 4
        case 7, 8: return 8
        default: return 2
        }
    }

    // MARK: - 文案映射

    static func describeExposureProgram(_ raw: Int) -> String {
        switch raw {
        case 1: return "M (手动)"
        case 2: return "P (程序)"
        case 3: return "A (光圈优先)"
        case 4: return "S (快门优先)"
        case 0x8010: return "Auto"
        case 0x8011: return "场景"
        case 0x8012: return "U1 / 遥控"
        case 0x8013: return "U2"
        case 0x8014: return "U3"
        default: return "未知(\(raw))"
        }
    }

    /// 镜头 ID → 名称。机身只给数字 ID，名称表在本地；未登记就显示「未知镜头」，
    /// **不编造型号**。这张表只覆盖常见机型，缺失是可接受的降级。
    static func lensName(fromId id: UInt32) -> String? {
        let table: [UInt32: String] = [
            0x0000: "无镜头",
            0x0101: "AF-S NIKKOR 24-70mm f/2.8E ED VR",
            0x0120: "AF-S NIKKOR 70-200mm f/2.8E FL ED VR",
            0x0130: "NIKKOR Z 24-70mm f/2.8 S",
            0x0140: "NIKKOR Z 70-200mm f/2.8 VR S",
            0x0150: "NIKKOR Z 50mm f/1.8 S",
            0x0151: "NIKKOR Z 35mm f/1.8 S",
            0x0152: "NIKKOR Z 24-120mm f/4 S"
        ]
        return table[id]
    }

    static let commonIsoValues: [Int] = [100, 200, 400, 800, 1_600, 3_200, 6_400, 12_800, 25_600, 51_200]
}
