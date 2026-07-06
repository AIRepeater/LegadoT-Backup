package io.legado.app.help.source

import cn.hutool.crypto.symmetric.AES
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 书源获取工具类
 * 用于从远程URL获取加密的书源数据并解密
 */
object BookSourceFetcher {

    private const val DEFAULT_URL = "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt"
    private const val DEFAULT_KEY = "diosfjckwpqdfjkvnqQjsik"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 获取MD5密钥的hex字符串
     * @param key 原始密钥字符串
     * @return MD5加密后的hex字符串
     */
    fun getMd5KeyHex(key: String = DEFAULT_KEY): String {
        return MD5Utils.md5Encode(key)
    }

    /**
     * 创建AES-ECB解密器
     * @param key 密钥字符串，默认使用DEFAULT_KEY
     * @return AES实例
     */
    fun createAesDecryptor(key: String = DEFAULT_KEY): AES {
        val md5Key = getMd5KeyHex(key)
        // 使用MD5密钥的前16字节(128位)作为AES密钥
        return AES(md5Key.toByteArray().copyOf(16))
    }

    /**
     * 从URL获取内容
     * @param url 要获取的URL，默认使用DEFAULT_URL
     * @return 获取到的原始内容字符串
     */
    suspend fun fetchContent(url: String = DEFAULT_URL): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP请求失败: ${response.code}")
            }
            response.body?.string() ?: throw RuntimeException("响应体为空")
        }
    }

    /**
     * 解密内容
     * @param encryptedData 加密的数据（Base64编码）
     * @param key 密钥字符串，默认使用DEFAULT_KEY
     * @return 解密后的字符串
     */
    fun decryptContent(encryptedData: String, key: String = DEFAULT_KEY): String {
        val aes = createAesDecryptor(key)
        return aes.decryptStr(encryptedData)
    }

    /**
     * 获取并解密书源数据
     * @param url 要获取的URL，默认使用DEFAULT_URL
     * @param key 密钥字符串，默认使用DEFAULT_KEY
     * @return 解密后的书源JSON字符串
     */
    suspend fun fetchAndDecrypt(
        url: String = DEFAULT_URL,
        key: String = DEFAULT_KEY
    ): String = withContext(Dispatchers.IO) {
        val encryptedContent = fetchContent(url)
        decryptContent(encryptedContent, key)
    }

    /**
     * 测试函数 - 用于验证解密逻辑
     */
    suspend fun testFetchAndDecrypt() {
        try {
            println("开始测试书源获取...")
            println("URL: $DEFAULT_URL")
            println("密钥: $DEFAULT_KEY")
            println("MD5密钥(hex): ${getMd5KeyHex()}")
            
            val decryptedContent = fetchAndDecrypt()
            println("解密成功！")
            println("内容长度: ${decryptedContent.length}")
            println("前500字符: ${decryptedContent.take(500)}")
        } catch (e: Exception) {
            println("测试失败: ${e.message}")
            e.printStackTrace()
        }
    }
}