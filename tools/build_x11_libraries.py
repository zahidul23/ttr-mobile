"""Source-build the Android X11 client and GLES translation libraries."""
from pathlib import Path
import os,re,shutil,subprocess,sys,json
from common import ROOT,TOOLS,find,run,checked_sources

BUILD=ROOT/'build/linux-libraries'


def cmake(path):return path.resolve().as_posix()


def main():
    checked_sources('termux-x11-source','libxcb-source','xcbproto-source','libxext-source')
    BUILD.mkdir(exist_ok=True)
    original=TOOLS/'termux-x11-source/lorie/src/main/cpp'
    x11=BUILD/'libx11'
    shutil.copytree(original/'libx11',x11,dirs_exist_ok=True,ignore=shutil.ignore_patterns('.git'))
    locale=x11/'include/X11/Xlocale.h';locale.replace(locale.with_name('XTtrLocale.h'))
    for p in x11.rglob('*'):
        if p.is_file() and p.suffix in ('.c','.h'):
            text=p.read_text(encoding='utf-8',errors='surrogateescape')
            if 'Xlocale.h' in text:p.write_text(text.replace('Xlocale.h','XTtrLocale.h'),encoding='utf-8',errors='surrogateescape')
    inc=BUILD/'include';(inc/'xcb').mkdir(parents=True,exist_ok=True);(inc/'X11').mkdir(exist_ok=True)
    xcb=TOOLS/'libxcb-source';proto=TOOLS/'xcbproto-source'
    for name in ('xcb.h','xcbext.h','xcbint.h'):
        shutil.copy2(xcb/'src'/name,inc/'xcb'/name)
    for name in ('xproto','bigreq','xc_misc'):
        run(sys.executable,xcb/'src/c_client.py','-p',proto,'-c','TTR Mobile','-l','XCB','-s','3',proto/'src'/f'{name}.xml',cwd=inc/'xcb')
    shutil.copy2(ROOT/'build/linux-x11/native/ks_tables.h',inc/'ks_tables.h')
    (inc/'X11/XlibConf.h').write_text('#define XTHREADS 1\n#define XUSE_MTSAFE_API 1\n')
    shutil.copytree(original/'libxtrans',inc/'X11/Xtrans',dirs_exist_ok=True)
    shutil.copy2(original/'xorgproto/include/X11/Xpoll.h.in',inc/'X11/Xpoll.h')
    # Source components use config.h for platform capabilities; no host headers.
    defines=['HAVE_CONFIG_H','HAVE_SENDMSG','USE_POLL','HAVE_SYS_SELECT_H','HAVE_SYS_SOCKET_H','HAVE_UNISTD_H','HAVE_STDINT_H','HAVE_STDLIB_H','HAVE_STRING_H','HAVE_STRINGS_H',
        'HAVE_DLFCN_H','HAVE_SYS_IOCTL_H','HAVE_SYS_SHM_H','HAVE_SYS_UN_H','HAVE_GETPAGESIZE','HAVE_GETADDRINFO','HAVE_CLOCK_GETTIME','HAVE_PTHREAD','HAVE_PTHREAD_STUBS','HAVE_PTHREAD_MUTEX_RECURSIVE','HAVE_STRCASECMP','HAVE_STRDUP','HAVE_REALLOCARRAY','HAVE_STRLCAT','HAVE_STRLCPY','HAVE_MEMMOVE','HAVE_ISSETUGID',
        'XTHREADS','XUSE_MTSAFE_API','XLOCALE','XCMS','XKB','UNIXCONN','TCPCONN','TRANS_CLIENT', 'GCC_HAS_VISIBILITY']
    config='\n'.join('#define '+d+' 1' for d in defines)
    config+='\n#define XCB_QUEUE_BUFFER_SIZE 16384\n#define XCB_QUEUE_SIZE 16384\n#define IOV_MAX 1024\n#define USE_FDS_BITS 1\n#define XLOCALEDIR "/nonexistent"\n#define XLOCALELIBDIR "/nonexistent"\n#define XERRORDB "/nonexistent"\n#define X11_DATADIR "/nonexistent"\n#define XCMSDIR "/nonexistent"\n#define PACKAGE_VERSION "1.8.12"\n'
    (inc/'config.h').write_text(config)
    src=[]
    make=(x11/'src/Makefile.am').read_text();block=make.split('libX11_la_SOURCES =',1)[1].split('#',1)[0]
    src+=[x11/'src'/name for name in re.findall(r'[A-Za-z0-9_]+\.c\b',block)]
    for folder in ('src/xcms','src/xkb','src/xlibi18n','modules/im/ximcp','modules/lc/def','modules/lc/gen','modules/lc/Utf8','modules/om/generic'):
        src+=list((x11/folder).glob('*.c'))
    src=[p for p in src if p.name not in ('XlcDL.c','XlcSL.c')]
    includes=[inc,inc/'xcb',x11/'include',x11/'include/X11',original/'xorgproto/include',original/'libxau/include',x11/'src',x11/'src/xcms',x11/'src/xkb',x11/'src/xlibi18n']
    content='cmake_minimum_required(VERSION 3.22)\nproject(ttr_libraries C)\nset(CMAKE_C_STANDARD 11)\n'
    content+='include_directories('+ ' '.join('"'+cmake(p)+'"' for p in includes)+')\n'
    content+='add_compile_definitions(HAVE_CONFIG_H _GNU_SOURCE)\nadd_compile_options(-Wno-error=implicit-function-declaration -Wno-error=incompatible-pointer-types)\n'
    content+='add_library(xcb SHARED '+ ' '.join('"'+cmake(p)+'"' for p in list((xcb/'src').glob('xcb_*.c'))+[inc/'xcb'/f'{n}.c' for n in ('xproto','bigreq','xc_misc')])+')\n'
    content+='add_library(Xau SHARED '+ ' '.join('"'+cmake(p)+'"' for p in (original/'libxau').glob('Au*.c'))+')\ntarget_link_libraries(xcb Xau)\n'
    content+='add_library(Xdmcp SHARED '+ ' '.join('"'+cmake(p)+'"' for p in (original/'libxdmcp').glob('*.c') if p.name!='Wraphelp.c')+')\ntarget_compile_definitions(Xdmcp PRIVATE HAVE_ARC4RANDOM_BUF=1)\ntarget_include_directories(Xdmcp PRIVATE "'+cmake(original/'libxdmcp/include')+'")\n'
    content+='add_library(X11 SHARED '+ ' '.join('"'+cmake(p)+'"' for p in src)+')\ntarget_compile_definitions(X11 PRIVATE XIM_t)\ntarget_link_libraries(X11 xcb dl)\n'
    ext=TOOLS/'libxext-source'
    content+='add_library(Xext SHARED '+ ' '.join('"'+cmake(p)+'"' for p in (ext/'src').glob('*.c'))+')\n'
    content+='target_include_directories(Xext PRIVATE "'+cmake(ext/'include')+'" "'+cmake(ext/'include/X11/extensions')+'")\ntarget_link_libraries(Xext X11)\n'
    content+='target_link_options(X11 PRIVATE -Wl,--no-undefined)\n'
    # Extension libraries Wine's X11 driver loads at runtime (screen size changes, colour cursors, input).
    for name,deps,extra in (('Xrender','X11',''),('Xfixes','X11',''),('Xrandr','Xrender Xext X11',''),
                            ('Xcursor','Xrender Xfixes X11','XCURSORPATH="/nonexistent" ICONDIR="/nonexistent"')):
        lib=TOOLS/('lib'+name.lower()+'-source')
        if not lib.exists():continue
        if name=='Xcursor':  # configure-generated header: fill in the release version (1.2.3)
            header=inc/'X11/Xcursor/Xcursor.h';header.parent.mkdir(parents=True,exist_ok=True)
            text=(lib/'include/X11/Xcursor/Xcursor.h.in').read_text()
            for k,v in (('MAJOR','1'),('MINOR','2'),('REVISION','3')):text=text.replace('#undef XCURSOR_LIB_'+k,'#define XCURSOR_LIB_'+k+' '+v)
            header.write_text(text)
        dirs=[inc/'X11/Xcursor',lib/'include',lib/'include/X11/extensions',lib/'src',ext/'include']+[TOOLS/('lib'+d.lower()+'-source')/'include' for d in deps.split() if d not in ('X11','Xext')]
        content+='add_library('+name+' SHARED '+' '.join('"'+cmake(p)+'"' for p in (lib/'src').glob('*.c'))+')\n'
        content+='target_include_directories('+name+' PRIVATE '+' '.join('"'+cmake(d)+'"' for d in dirs)+')\n'
        content+='target_link_libraries('+name+' '+deps+')\n'
        if extra:content+='target_compile_definitions('+name+' PRIVATE '+extra+')\n'
    (BUILD/'CMakeLists.txt').write_text(content)
    ndk=TOOLS/'android-ndk/android-ndk-r28c'
    args=['-G','Ninja','-DCMAKE_TOOLCHAIN_FILE='+cmake(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-29','-DCMAKE_BUILD_TYPE=Release']
    run('cmake','-S',BUILD,'-B',BUILD/'native',*args)
    run('cmake','--build',BUILD/'native','-j','6')


if __name__=='__main__':main()
