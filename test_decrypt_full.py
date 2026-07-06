#!/usr/bin/env python3
"""测试多种可能的解密方式"""

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

# 尝试不同的密钥生成方式
print("\n" + "="*60)
print("测试方式1: MD5 hex字符串的前16字符作为UTF-8字节")
print("="*60)
aes_key1 = md5_hex[:16].encode('utf-8')
test_decrypt(aes_key1, encrypted_data, "方式1")

print("\n" + "="*60)
print("测试方式2: MD5的原始16字节（从hex转换）")
print("="*60)
aes_key2 = bytes.fromhex(md5_hex)
test_decrypt(aes_key2, encrypted_data, "方式2")

print("\n" + "="*60)
print("测试方式3: MD5 hex字符串的前16字符作为ASCII字节")
print("="*60)
aes_key3 = md5_hex[:16].encode('ascii')
test_decrypt(aes_key3, encrypted_data, "方式3")

print("\n" + "="*60)
print("测试方式4: 使用MD5原始字节的前16字节")
print("="*60)
md5_bytes = hashlib.md5(key_str.encode()).digest()
aes_key4 = md5_bytes[:16]
test_decrypt(aes_key4, encrypted_data, "方式4")

def test_decrypt(key, encrypted_base64, method_name):
    print(f"密钥(hex): {key.hex()}")
    print(f"密钥长度: {len(key)}")
    
    try:
        # Base64解码
        encrypted_bytes = base64.b64decode(encrypted_base64)
        print(f"Base64解码后长度: {len(encrypted_bytes)}")
        
        # AES-ECB解密
        cipher = AES.new(key, AES.MODE_ECB)
        decrypted_bytes = cipher.decrypt(encrypted_bytes)
        
        print(f"解密后字节长度: {len(decrypted_bytes)}")
        print(f"前32字节(hex): {decrypted_bytes[:32].hex()}")
        
        # 尝试移除不同的padding
        paddings = [
            ("PKCS7", lambda data: data[:-data[-1]] if data[-1] <= 16 else data),
            ("No padding", lambda data: data),
            ("Zero padding", lambda data: data.rstrip(b'\x00')),
        ]
        
        for padding_name, remove_padding in paddings:
            try:
                unpadded = remove_padding(decrypted_bytes)
                text = unpadded.decode('utf-8')
                print(f"\n✓ {padding_name} 成功!")
                print(f"内容长度: {len(text)}")
                print(f"前100字符: {text[:100]}")
                return True
            except Exception as e:
                continue
        
        # 尝试其他编码
        for encoding in ['gbk', 'gb18030', 'latin1']:
            try:
                text = decrypted_bytes.decode(encoding)
                print(f"\n✓ 使用{encoding}解码成功")
                print(f"内容长度: {len(text)}")
                print(f"前100字符: {text[:100]}")
                return True
            except:
                continue
        
        print("✗ 所有解码方式都失败")
        return False
        
    except Exception as e:
        print(f"✗ 解密失败: {e}")
        return False