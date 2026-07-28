//
//  ViewController.m
//  secure-communication-ios
//
//  Created by yaoqi.li on 2023/3/5.
//

#import "ViewController.h"
#import "CTSecureCommunication.h"

@interface ViewController ()
@property (weak, nonatomic) IBOutlet UITextView *viewResponse;

@end

@implementation ViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    // Do any additional setup after loading the view.
}

- (IBAction)buttonApiTest:(id)sender {
    _viewResponse.text = [CTSecureCommunication post:@"/1/1" withHeader:@"Content-Type:application/json\r\ncode:2042067e-abc6-4961-8fb0-8f9f21e2bc6e-ios-1-230501\r\n" withBody:@"{\"common\":[\"userid\",\"2090E6F6-CE5E-484A-9FA2-77DB008208D4\",\"1684394081000\",\"1.0.0.220519\",1,\"测试app\",\"com.abc.test\",\"1.0.1\",\"ios\",\"apple\",\"iphoen8,1\",\"ios\",\"13.1\",\"wifi\",\"192.168.12.1\",\"10.22.22.22\",31.207207207207208,107.41724717894603,\"四川省\",\"达州市\",\"通川区\",\"夏家镇\",\"xxxx\",\"2023-03-29 06:00:39\"]}"];
}

@end
