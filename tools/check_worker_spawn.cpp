#include "../app/src/main/cpp/worker-spawn-fast.h"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static double ms(){timespec t{};clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000.+t.tv_nsec/1e6;}
static int errors=0;
static void check(bool ok,const char* what){std::printf("%s: %s\n",ok?"ok":"FAIL",what);errors+=!ok;}
int main(int argc,char** argv){
    if(argc>1&&std::strcmp(argv[1],"child")==0){
        int count=std::atoi(argv[2]),unrelated=std::atoi(argv[3]);
        for(int i=0;i<count;++i){char c=0;if(pread(3+i,&c,1,0)!=1||c!=char(33+i%80))return 10;}
        if(fcntl(unrelated,F_GETFD)!=-1||errno!=EBADF)return 11;
        sigset_t mask;sigprocmask(SIG_SETMASK,nullptr,&mask);if(sigismember(&mask,SIGUSR1))return 12;
        const char* value=std::getenv("SCAM_SPAWN_CHECK");if(!value||std::strcmp(value,"exact"))return 13;
        std::puts("child stdout");std::fprintf(stderr,"child stderr\n");return 0;
    }
    check(scam_spawn::api().available(),"Android fast-spawn API available");
    if(errors)return errors;
    {
        int null=open("/dev/null",O_RDONLY|O_CLOEXEC);
        char exe[]="/system/bin/true";char* args[]={exe,nullptr};char* env[]={nullptr};
        int inherited=getpriority(PRIO_PROCESS,0);
        check(scam_spawn::fast(args,env,{},null,null,inherited==19?18:inherited+1)==-1,
                "different requested nice refuses fast spawn without creating a child");
        char missing[]="/scamera/no-such-executable";args[0]=missing;
        pid_t pid=scam_spawn::fast(args,env,{},null,null,INT_MIN);
        int status=0;if(pid>0)waitpid(pid,&status,0);
        check(pid==-1||(pid>0&&WIFEXITED(status)&&WEXITSTATUS(status)==127),"exec failure reported or child exits 127");
        close(null);
    }
    for(int count:{0,3,82}){
        std::vector<int> sources;
        for(int i=0;i<count;++i){
            char name[]="/data/local/tmp/scamera-spawn-check-XXXXXX";
            int fd=mkstemp(name);unlink(name);char c=char(33+i%80);write(fd,&c,1);
            int high=fcntl(fd,F_DUPFD_CLOEXEC,std::max(128,3+count));close(fd);sources.push_back(high);
        }
        int pipeFds[2];pipe2(pipeFds,O_CLOEXEC);
        int devNull=open("/dev/null",O_RDONLY|O_CLOEXEC);
        int unrelated=fcntl(devNull,F_DUPFD,400);
        sigset_t mask,old;sigemptyset(&mask);sigaddset(&mask,SIGUSR1);sigprocmask(SIG_BLOCK,&mask,&old);
        std::string n=std::to_string(count),u=std::to_string(unrelated);
        char child[]="child",environment[]="SCAM_SPAWN_CHECK=exact";
        char* args[]={argv[0],child,n.data(),u.data(),nullptr};char* env[]={environment,nullptr};
        pid_t pid=scam_spawn::fast(args,env,sources,pipeFds[1],devNull,INT_MIN);
        close(pipeFds[1]);sigprocmask(SIG_SETMASK,&old,nullptr);
        int status=0;if(pid>0)waitpid(pid,&status,0);
        char bytes[1024]{};ssize_t got=read(pipeFds[0],bytes,sizeof(bytes)-1);
        check(pid>0&&WIFEXITED(status)&&WEXITSTATUS(status)==0,"child fd mapping / unrelated close / signal mask / explicit environment");
        check(got>0&&std::strstr(bytes,"child stdout")&&std::strstr(bytes,"child stderr"),"stdout and stderr reach the same pipe");
        for(int fd:sources)close(fd);close(pipeFds[0]);close(devNull);close(unrelated);
    }
    // Touch enough private memory to expose page-table copying, not merely the cost of an empty fork.
    const size_t size=size_t(512)<<20;
    void* memory=mmap(nullptr,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    check(memory!=MAP_FAILED,"large parent allocation");
    if(memory!=MAP_FAILED){
        std::memset(memory,37,size);
        std::vector<double> forkTimes,fastTimes;
        for(int run=0;run<12;++run)for(int mode=0;mode<2;++mode){
            int fds[2];pipe2(fds,O_CLOEXEC);int null=open("/dev/null",O_RDONLY|O_CLOEXEC);
            char exe[]="/system/bin/true";char* args[]={exe,nullptr};char* env[]={nullptr};
            double t=ms();pid_t pid;
            if(mode)pid=scam_spawn::fast(args,env,{},fds[1],null,INT_MIN);
            else {pid=fork();if(pid==0){execl(exe,exe,nullptr);_exit(127);}}
            double elapsed=ms()-t;int status=0;if(pid>0)waitpid(pid,&status,0);
            check(pid>0&&WIFEXITED(status)&&WEXITSTATUS(status)==0,"timed child exits");
            (mode?fastTimes:forkTimes).push_back(elapsed);
            close(fds[0]);close(fds[1]);close(null);
        }
        std::sort(forkTimes.begin(),forkTimes.end());std::sort(fastTimes.begin(),fastTimes.end());
        std::printf("512 MiB parent spawn median: fork %.3f ms, fast %.3f ms\n",forkTimes[6],fastTimes[6]);
        munmap(memory,size);
    }
    return errors?1:0;
}
