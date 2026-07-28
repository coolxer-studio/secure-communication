#### 一、集成方式
集成支持oc和swift语言的app或者framework工程，基本步骤是一样的，只是调用代码的语法不一样，过程如下：  
1、拖动sc.framework到需要引入的工程根目录  
2、设置：General->sc.framework->Embed&Sign  
3、oc代码调用   
```
#import "ViewController.h"
// 引入头文件
#import "sc/sc.h"

@interface ViewController ()

@end
@implementation ViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    // 调用函数
    NSLog(@"get  : %@",[Entrance ping:@"hello sc"]);
}
@end
```
4、swift代码调用（不需要桥接头文件）   
```
import UIKit
// 引入framework
import sc

class ViewController: UIViewController {

    override func viewDidLoad() {
        super.viewDidLoad()
        // 调用代码
        print(Entrance.ping("hello sc"))
    }


}
```

##### FAQ

1、需要设置framework嵌入并且签名
报错：XXX/Frameworks/sc.framework/sc' (no such file)
解决：General，把对用的framework的Embed切换到Embed&Sign
2、Xcode12.3之后要求真机和模拟器混编的需要是xcframework格式，否则会提示
报错：XXX Building for iOS Simulator, but the linked and embedded framework 'sc.framework' was built for iOS + iOS Simulator.
解决：Buil Settings - Build Options - Validate Workspace 改为Yes

#### 二、参数配置
无

#### 三、开发调试
1、使用xcode倒入整个工程  
2、修改代码
3、app运行测试：target选择iossc，直接运行。手动打包Framework：target选择sc，product->build->show build folder in finder    
4、脚本打包发布包（合并真机和模拟器架构）：./build.sh -t sc  

#### 四、核心原理
1、项目是基于oc接口包装，核心逻辑用c语言开发  
2、uri使用随机密钥做sm4加密后转base32混合密钥、body使用固定密钥做sm4加密后转base64  



