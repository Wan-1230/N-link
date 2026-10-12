package com.camerakit.ptp

/**
 * APP 扩展文件：宿主 App 相册 / 监看功能所需的 PTP 操作封装。
 *
 * 不属于连接核心（握手 / 会话 / 读取验证）范围，仅在既有 [PtpSession] 之上补充
 * 宿主功能用到的命令；连接逻辑不变。语义与开源 PTP 协议实现的操作码定义一致。
 */

/** GetObjectInfo(0x1008) 原始数据集字节（宿主侧按完整 ObjectInfo 结构解析）。 */
suspend fun PtpSession.rawObjectInfo(handle: Long): ByteArray =
    rawTransact(PtpConsts.OP_GET_OBJECT_INFO, listOf(handle and 0xFFFFFFFFL)).dataIn ?: ByteArray(0)

/** GetDevicePropValue(0x1015) 原始值；读取失败返回 null（不抛错）。 */
suspend fun PtpSession.getDevicePropValue(code: Int): ByteArray? = runCatching {
    rawTransact(PtpConsts.OP_GET_DEVICE_PROP_VALUE, listOf(code.toLong() and 0xFFFFFFFFL)).dataIn
}.getOrNull()

/** SetDevicePropValue(0x1016)；失败返回 false（不抛错）。 */
suspend fun PtpSession.setDevicePropValue(code: Int, value: ByteArray): Boolean = runCatching {
    rawTransact(
        PtpConsts.OP_SET_DEVICE_PROP_VALUE,
        listOf(code.toLong() and 0xFFFFFFFFL),
        dataOut = value,
    ).isOk
}.getOrDefault(false)

/**
 * 高清缩略图（尼康扩展 0x90C4）；机身不支持或读取失败返回 null，由调用方回退标准缩略图。
 * 操作码未收录于 [PtpConsts]，在此以常量字面量声明。
 */
private const val OP_NIKON_GET_LARGE_THUMB = 0x90C4

suspend fun PtpSession.getLargeThumbnail(handle: Long): ByteArray? = runCatching {
    rawTransact(OP_NIKON_GET_LARGE_THUMB, listOf(handle and 0xFFFFFFFFL)).dataIn
}.getOrNull()

/** 监看帧（0x9203）：原始载荷（可能带前导头，JPEG 起点由调用方定位）；失败返回 null。 */
suspend fun PtpSession.getLiveViewImage(): ByteArray? = runCatching {
    rawTransact(PtpConsts.OP_NIKON_GET_LIVE_VIEW_IMAGE, timeoutMs = 10_000).dataIn
}.getOrNull()
