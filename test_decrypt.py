#!/usr/bin/env python3
"""测试AES-ECB解密逻辑"""

import hashlib
from Crypto.Cipher import AES
import base64

# 原始密钥
key_str = "diosfjckwpqdfjkvnqQjsik"

# 计算MD5的hex字符串
md5_hex = hashlib.md5(key_str.encode()).hexdigest()
print(f"原始密钥: {key_str}")
print(f"MD5 hex: {md5_hex}")
print(f"MD5 hex长度: {len(md5_hex)}")

# 获取加密内容
encrypted_data = "X+bnzYIcwF6C7Rd3T7njPDNH08zsH9zyqCrrjCr7qcnHb1LsmIZGIHtrNVR/GiraHE6OuhvrxEzwciVvhdU0I9OYcmWTxF1K7fLfcwkn7kMQg2DZ2qpE7dKGkqKCmQijaSUOswxL1/p9pSVe/vRYEzbB5pfcAB6Yz/zVVIendBJK629QiqQndRXM9bijtZuYQGhQS91cgMlYTujl5ouyy85KZvbdoT5y5xdlQSRMA5/8Pb/+EfGpFWkk1iziXNJycvvb5trdlvfe73KhS/dYpJSrilRGR07XaWi04FhMV2i47SM9s5VWX8t42K8yme8fiy+6NNnOlgeB1evbNVagiWJft5d0TcA4ydIkTZxvaf3GmS/8urE/Iu/FnN/FmYTgMZA7DhYhjab0t3qIygNMBoaU7VrLlK14Eme4pRD7bSbzoayRhkSSe1MeB4Mw2DtQ"

print(f"\n加密数据长度: {len(encrypted_data)}")

# 尝试两种方式
print("\n=== 尝试方式1：使用MD5 hex字符串的前16字符的UTF-8字节 ===")
aes_key1 = md5_hex[:16].encode('utf-8')
print(f"AES密钥: {aes_key1.hex()}")
cipher1 = AES.new(aes_key1, AES.MODE_ECB)
encrypted_bytes = base64.b64decode(encrypted_data)
decrypted_bytes1 = cipher1.decrypt(encrypted_bytes)
print(f"解密后字节(前32): {decrypted_bytes1[:32].hex()}")

print("\n=== 尝试方式2：使用MD5的原始字节（从hex转换） ===")
aes_key2 = bytes.fromhex(md5_hex)
print(f"AES密钥: {aes_key2.hex()}")
cipher2 = AES.new(aes_key2, AES.MODE_ECB)
decrypted_bytes2 = cipher2.decrypt(encrypted_bytes)
print(f"解密后字节(前32): {decrypted_bytes2[:32].hex()}")

# 检查哪种方式能正确解码为UTF-8
for idx, decrypted_bytes in enumerate([decrypted_bytes1, decrypted_bytes2], 1):
    try:
        # 移除padding
        padding_length = decrypted_bytes[-1]
        if padding_length <= 16:
            decrypted_bytes = decrypted_bytes[:-padding_length]
        
        decrypted_text = decrypted_bytes.decode('utf-8')
        print(f"\n=== 方式{idx}成功！===")
        print(f"解密后内容长度: {len(decrypted_text)}")
        print(f"前500字符:\n{decrypted_text[:500]}")
    except:
        pass

