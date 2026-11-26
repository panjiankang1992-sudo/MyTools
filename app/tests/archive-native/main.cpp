#include "archive_engine_core.h"
#include <iostream>
#include <thread>
int main(int argc,char**argv) {
 if(argc<2)return 2;
 mytools_archive::Request request; request.source=argv[1];
 if(argc>2)request.password=argv[2];
 if(argc>3){request.extract=true;request.output=argv[3]; for(int i=4;i<argc;i++)request.selected.push_back(std::stoul(argv[i]));}
 mytools_archive::State state;
 if(std::getenv("ARCHIVE_TEST_CANCEL"))state.cancel=true;
 std::thread canceller;
 // 等待真实输出后取消，验证运行中的解码而不是仅验证启动前取消。
 if(std::getenv("ARCHIVE_TEST_CANCEL_DURING"))canceller=std::thread([&state]{
   while(state.running && state.bytes==0)std::this_thread::yield();
   if(state.running)state.cancel=true;
 });
 const auto result=mytools_archive::Run(request,state);
 if(canceller.joinable())canceller.join();
 std::cout<<result<<std::endl;
}
