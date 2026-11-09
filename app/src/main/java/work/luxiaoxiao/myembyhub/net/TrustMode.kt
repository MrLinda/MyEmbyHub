package work.luxiaoxiao.myembyhub.net

/**
 * 证书信任模式。
 *
 * 正常情况只用 [BUNDLED]（内置 Let's Encrypt 锚点）；
 * [ALL] 只在排查证书问题时用（会信任任意证书，**不要**在正式客户端里默认开启）。
 */
enum class TrustMode {
    ALL,
    BUNDLED
}
