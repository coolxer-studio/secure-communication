# @coolxer/secure-communication-js

浏览器端 H5 通信编解码 SDK，与本仓库 Spring Boot Starter 的 `/sc/h5/**` 通道兼容。

> 该协议只提供现有业务所需的报文加密兼容能力，不替代 HTTPS，也不提供消息认证、防篡改或防重放能力。

## 安装

当前仓库尚未发布 npm 版本。在相邻的 `agent-h5` 项目中可使用本地依赖：

```json
{
  "dependencies": {
    "@coolxer/secure-communication-js": "file:../../../secure-communication/client/javascript"
  }
}
```

也可以在 SDK 目录生成安装包：

```bash
npm install
npm test
npm pack
```

## 使用

```js
import { createH5Codec } from '@coolxer/secure-communication-js';

const codec = createH5Codec('1596861234c4ea6ddd041d45b3912345');
const requestBody = codec.encodeRequest(JSON.stringify({ message: 'hello' }));

// 将 requestBody POST 到 /sc/h5/** 后，解密非空响应：
const response = JSON.parse(codec.decodeResponse(responseCipherHex));
```

`encrypt` 和 `decrypt` 只处理 Hex 密文，不拼接 appId，可用于需要与通信协议保持同一算法的本地数据：

```js
const cipherHex = codec.encrypt('text');
const plainText = codec.decrypt(cipherHex);
```

## API

### `createH5Codec(appId)`

`appId` 必须是长度为 32 的字符串。返回：

- `encrypt(plainText)`：使用 SM4-CBC 加密字符串，返回大写 Hex。
- `decrypt(cipherHex)`：解密大小写均可的 Hex，返回 UTF-8 字符串。
- `encodeRequest(plainText)`：返回 `encrypt(plainText) + appId.toUpperCase()`。
- `decodeResponse(cipherHex)`：解密服务端 H5 通道返回的 Hex。

非法 appId、非字符串明文、非法 Hex、错误块长度或无效 PKCS#7 padding 会抛出异常。

## 协议

```text
key = MD5(lowercase(appId) + "_bsdk_")[0..15]
iv = key
request = UPPER_HEX(SM4-CBC-PKCS7(UTF8(plainText), key, iv))
          + uppercase(appId)
response = HEX(SM4-CBC-PKCS7(UTF8(plainText), key, iv))
```

为兼容已经部署的 Spring Boot H5 实现，SDK 保留了服务端轮转函数中的算术右移行为。它与标准 SM4 实现存在差异，不能在没有固定向量验证的情况下直接替换为通用 SM4 库。

构建产物包含 CommonJS、ES Module 和 UMD 三种入口。直接通过 `<script>` 加载 UMD 文件时，API 位于 `window.SecureCommunicationJS`。
