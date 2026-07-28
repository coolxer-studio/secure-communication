#### 一、集成方式
直接在spring boot项目的pom中添加依赖即可完成集成：  
```
<dependency>
	<groupId>org.example</groupId>
	<artifactId>spring-boot-starter-sc</artifactId>
	<version>1.0-SNAPSHOT</version>
</dependency>
```
#### 二、参数配置
如果有特殊需求可以在spring boot项目的配置文件中重写参数，将覆盖默认值：

| 参数                        |                        说明 |  默认值  |
|:--------------------------|--------------------------:|:-----:|
| spring.sc.enabled         |                    是否开启sc | true  |
| spring.sc.prefix          |                   sc接口的前缀 |  /sc  |
| spring.sc.repeatQueueSize |          去重uri队列大小，为0代表关闭 | 1000  |
| spring.sc.urlObfuscate    |                   uri混淆参数 |  待补充  |
| spring.sc.encryption      |                    数据加密参数 |  待补充  |
| spring.sc.identify        |  信任协议标识，为空代表关闭，多个字符串','分隔 |   空   |

#### 三、开发调试
1、使用idea等开发工具，按照maven项目倒入当前工程  
2、修改代码  
3、发布包：mvn clean install (mvn clean deploy)  
#### 四、核心原理
1、项目是基于spring starter的spi机制实现依赖和配置的合成（使用者直接引用依赖即可使用）  
2、数据加解密等逻辑都是在servlet的filter中实现（尝试用interceptor的时候一直无法生效）  
3、uri使用随机密钥做sm4加密后转base32混合密钥、body使用固定密钥做sm4加密后转base64  

