"""Build GL4ES GLX over Android GLES using a pbuffer and X11 presentation.

This path has a per-frame readback/copy cost; it is a compatibility experiment,
not a zero-copy Vulkan or performance claim.
"""
import shutil,subprocess
from common import ROOT,TOOLS,run

COMMIT='2037865590d410bcd7b8d1f22b841472ed6d39ac'


def main():
    source=TOOLS/'gl4es-source-host';build=ROOT/'build/linux-graphics';copy=build/'source'
    if subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()!=COMMIT:raise RuntimeError('GL4ES revision mismatch')
    shutil.copytree(source,copy,dirs_exist_ok=True,ignore=shutil.ignore_patterns('.git','lib','bin'))
    p=copy/'CMakeLists.txt';text=p.read_text().replace('add_definitions(-DNOX11 -DNO_GBM -DDEFAULT_ES=2)','add_definitions(-DNO_GBM -DDEFAULT_ES=2)')
    includes=[ROOT/'build/linux-libraries/include',ROOT/'build/linux-libraries/libx11/include',TOOLS/'termux-x11-source/lorie/src/main/cpp/xorgproto/include']
    text+='\ninclude_directories('+ ' '.join('"'+p.as_posix()+'"' for p in includes)+')\n'
    # Directory include properties must precede add_subdirectory(src).
    marker='add_subdirectory(src)'
    text=text.replace(marker,'include_directories('+ ' '.join('"'+p.as_posix()+'"' for p in includes)+')\n'+marker)
    p.write_text(text)
    p=copy/'src/CMakeLists.txt';p.write_text(p.read_text().replace('MATCHES "Linux"','MATCHES "Linux|Android"').replace('target_link_libraries(GL X11 m dl)',
        'target_link_libraries(GL "'+(ROOT/'build/linux-libraries/native/libX11.so').as_posix()+'" m dl)'))
    p=copy/'src/glx/glx.c';text=(p.read_text().replace('FindGBMConfig(eglDisplay, fake->eglConfigs, fake->eglConfigsCount)','0 /* GBM is disabled in this Android pbuffer build */')
        .replace('context->eglConfigs[0], drawable, attrib_list','context->eglConfigs[0], (EGLNativeWindowType)(uintptr_t)drawable, attrib_list'))
    # Present window frames through a GPU-shared buffer (ttr_present.c) instead of reading them back;
    # GL4ES's own copy remains the fallback. Window pbuffers get a minimum size so windows can grow.
    swap='''    if(PBuffer && glstate->emulatedPixmap) {
        LOAD_GLES(glFinish);
        gles_glFinish();
        BlitEmulatedPixmap(drawable);
    } else'''
    size='''                    egl_attribs[i++] = EGL_WIDTH;
                    egl_attribs[i++] = width;
                    egl_attribs[i++] = EGL_HEIGHT;
                    egl_attribs[i++] = height;'''
    for anchor in (swap,size,'static int pbufferlist_size = 0;'):
        if text.count(anchor)!=1:raise RuntimeError('GL4ES patch anchor not found')
    text=text.replace('static int pbufferlist_size = 0;','static int pbufferlist_size = 0;\nint ttr_present(unsigned long window, int surfaceWidth, int surfaceHeight);\nint ttr_surface_size(int value, const char *name);\nint ttr_gl_log_enabled(void);\nvoid ttr_gl_log(const char *format, ...);')
    # Use the swapped drawable's own surface (PBuffer), not the one the context first used.
    text=text.replace(swap,'''    if(PBuffer && glstate->emulatedPixmap) {
        glx_buffSize *ttr_buff = &pbuffersize[PBuffer-1];
        if(ttr_gl_log_enabled()) { static unsigned long ttr_last; if(ttr_last!=(unsigned long)drawable) { ttr_last=(unsigned long)drawable;
            ttr_gl_log("swap drawable=0x%lx surface=%d type=%d %dx%d context-surface=%d", (unsigned long)drawable, PBuffer, ttr_buff->Type, ttr_buff->Width, ttr_buff->Height, glstate->emulatedPixmap); } }
        if(ttr_buff->Type==4 && ttr_present((unsigned long)drawable, ttr_buff->Width, ttr_buff->Height)) {
            ttr_follow_window(display, PBuffer-1, drawable); /* GL4ES's own copy did this; keep the surface the window's size */
        } else {
            LOAD_GLES(glFinish);
            gles_glFinish();
            BlitEmulatedPixmap(drawable);
        }
    } else''')
    text=text.replace(size,'''                    egl_attribs[i++] = EGL_WIDTH;
                    egl_attribs[i++] = ttr_surface_size(width, "TTR_GL_MIN_WIDTH");
                    egl_attribs[i++] = EGL_HEIGHT;
                    egl_attribs[i++] = ttr_surface_size(height, "TTR_GL_MIN_HEIGHT");''')
    # After a GPU-presented frame, resize the window's pbuffer to the window (as BlitEmulatedPixmap does).
    blitter='void BlitEmulatedPixmap(int win) {'
    if text.count(blitter)!=1:raise RuntimeError('GL4ES blit anchor not found')
    text=text.replace(blitter,'''static void ttr_follow_window(Display *dpy, int index, GLXDrawable drawable) {
    glx_buffSize *buff = &pbuffersize[index];
    unsigned int width, height, border, depth; Window root; int x, y;
    if(!XGetGeometry(dpy, drawable, &root, &x, &y, &width, &height, &border, &depth)) return;
    if(!width || !height || (width==(unsigned)buff->Width && height==(unsigned)buff->Height)) return;
    LOAD_EGL(eglDestroySurface); LOAD_EGL(eglMakeCurrent); LOAD_EGL(eglCreatePbufferSurface);
    EGLint attribs[] = {EGL_WIDTH, (EGLint)width, EGL_HEIGHT, (EGLint)height, EGL_NONE};
    EGLSurface surface = egl_eglCreatePbufferSurface(eglDisplay, buff->Config, attribs);
    if(surface==EGL_NO_SURFACE) return;
    egl_eglMakeCurrent(eglDisplay, surface, surface, buff->Context);
    egl_eglDestroySurface(eglDisplay, buff->Surface);
    if(buff->frame) { XDestroyImage(buff->frame); buff->frame = NULL; }
    glxContext->eglSurface = buff->Surface = surface; buff->Width = width; buff->Height = height;
    khint_t k = kh_get(mapdrawable, MapDrawable, drawable);
    if(k!=kh_end(MapDrawable)) kh_value(MapDrawable, k)->surface = surface;
    if(ttr_gl_log_enabled()) ttr_gl_log("window surface resized to %ux%u", width, height);
}
'''+blitter,1)
    text=text.replace('int ttr_present(unsigned long window, int surfaceWidth, int surfaceHeight);','int ttr_present(unsigned long window, int surfaceWidth, int surfaceHeight);\nstatic void ttr_follow_window(Display *dpy, int index, GLXDrawable drawable);',1)
    # Diagnostics (TTR_GL_LOG): the fixed-function shaders GL4ES generates (once per program).
    p2=copy/'src/gl/fpe.c';fpe=p2.read_text()
    frag='''                gl4es_glShaderSource(glstate->fpe->frag, 1, fpe_FragmentShader(NULL, glstate->fpe_state), NULL);'''
    if fpe.count(frag)!=1:raise RuntimeError('GL4ES fpe anchor not found')
    fpe=fpe.replace(frag,frag+'''
                if(ttr_gl_log_enabled()) { ttr_gl_log("fpe program\\n--- vertex ---\\n%s", fpe_VertexShader(NULL, glstate->fpe_state)[0]);
                    ttr_gl_log("--- fragment ---\\n%s\\n--- end ---", fpe_FragmentShader(NULL, glstate->fpe_state)[0]); }''')
    bound='''        glstate->fpe_bound_changed = 0;
    }'''
    if fpe.count(bound)!=1:raise RuntimeError('GL4ES fpe bound anchor not found')
    fpe=fpe.replace(bound,bound+'''
    if(ttr_gl_log_enabled() && !glstate->glsl->program) {
        /* Diagnostics: textures and recorded formats per unit at draw time (each distinct combination once). */
        static unsigned int seen[512]; static int seenCount;
        char line[512]; int at = 0; unsigned int hash = 2166136261u;
        for(int i=0; i<2; i++) {
            int texunit = fpe_gettexture(i);
            gltexture_t* tex = (texunit==-1)?NULL:glstate->texture.bound[i][texunit];
            if(!tex) continue;
            vertexattrib_t *ttr_tc = &glstate->vao->vertexattrib[ATT_MULTITEXCOORD0+i];
            at += snprintf(line+at, sizeof(line)-at, " unit%d tex=%u %dx%d fmt=%d type=%d state=%d/%d coords=%d/%d/0x%x/%d texmat=%d", i, tex->texture, tex->width, tex->height, tex->fpe_format, texunit,
                glstate->fpe_state->texture[i].texformat, glstate->fpe_state->texture[i].textype, ttr_tc->enabled, ttr_tc->size, ttr_tc->type, ttr_tc->stride, glstate->fpe_state->texture[i].texmat);
            hash = (hash ^ tex->texture ^ (tex->fpe_format<<16) ^ (i<<24)) * 16777619u;
        }
        int known = 0; for(int k=0; k<seenCount; k++) if(seen[k]==hash) { known = 1; break; }
        if(!known && seenCount<512 && at) { seen[seenCount++] = hash; ttr_gl_log("draw%s lighting=%d", line, glstate->enable.lighting); }
    }''')
    fpe='int ttr_gl_log_enabled(void);\nvoid ttr_gl_log(const char *format, ...);\n'+fpe
    p2.write_text(fpe)
    # Diagnostics (TTR_GL_LOG): every pbuffer GL4ES creates, and every surface it makes current.
    created='''    (*Surface) = egl_eglCreatePbufferSurface(eglDisplay, Config[0], egl_attribs);'''
    current='''        result = egl_eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglCtx);'''
    for anchor in (created,current):
        if text.count(anchor)!=1:raise RuntimeError('GL4ES log anchor not found')
    text=text.replace(created,created+'''
    if(ttr_gl_log_enabled()) { int ttr_w=0, ttr_h=0; for(int k=0; egl_attribs[k]!=EGL_NONE; k+=2) { if(egl_attribs[k]==EGL_WIDTH) ttr_w=egl_attribs[k+1]; if(egl_attribs[k]==EGL_HEIGHT) ttr_h=egl_attribs[k+1]; }
        ttr_gl_log("pbuffer created %dx%d surface=%p caller=%p", ttr_w, ttr_h, *Surface, __builtin_return_address(0)); }''')
    text=text.replace(current,current+'''
        if(ttr_gl_log_enabled()) ttr_gl_log("make current drawable=0x%lx surface=%p created=%d type=%d", (unsigned long)drawable, eglSurf, created, created?pbuffersize[created-1].Type:0);''')
    p.write_text(text)
    shutil.copy2(ROOT/'app/native/ttr_present.c',copy/'src/glx/ttr_present.c')
    # Upstream bug: realize_textures picked each unit's texture type from the *active* unit's enables, so a
    # 1D texture enabled on unit 1 (Panda's alpha-scale ramp) replaced unit 0's 2D texture. TTR showed black
    # or gray boxes for dimmed GUI (SpeedChat, disabled options, laff digits).
    p=copy/'src/gl/texture_params.c';text=p.read_text()
    wrong='        int tmp = glstate->enable.texture[glstate->texture.active];'
    if text.count(wrong)!=1:raise RuntimeError('GL4ES realize_textures anchor not found')
    p.write_text(text.replace(wrong,'        int tmp = glstate->enable.texture[i];'))
    p=copy/'src/gl/framebuffers.c';text=p.read_text()
    gen='''        gles_glGenerateMipmap(rtarget);
        bound->mipmap_auto = 1;'''
    if text.count(gen)!=1:raise RuntimeError('GL4ES mipmap anchor not found')
    p.write_text('int ttr_gl_log_enabled(void);\nvoid ttr_gl_log(const char *format, ...);\n'+text.replace(gen,gen+'''
        if(ttr_gl_log_enabled()) { LOAD_GLES(glGetError); ttr_gl_log("genmipmap tex=%u err=0x%x", bound->texture, gles_glGetError()); }'''))
    # Optional upload log (TTR_GL_LOG): every 2D texture upload with the driver's error code.
    p=copy/'src/gl/texture.c';text=p.read_text()
    upload='''                gles_glTexImage2D(rtarget, level, format, width, height, border,
                                format, type, pixels);'''
    if text.count(upload)!=1:raise RuntimeError('GL4ES texture patch anchor not found')
    text=text.replace(upload,upload+'''
                if(ttr_gl_log_enabled()) { LOAD_GLES(glGetError); GLenum ttr_err=gles_glGetError();
                    ttr_gl_log("tex2d tex=%u lvl=%d internal=0x%x fmt=0x%x type=0x%x %dx%d min=0x%x mip_need=%d mip_auto=%d err=0x%x",
                        bound->texture, level, internalformat, format, type, width, height, bound->sampler.min_filter, bound->mipmap_need, bound->mipmap_auto, ttr_err); }''')
    sub='''        gles_glTexSubImage2D(rtarget, level, xoffset, yoffset,
                     width, height, format, type, pixels);'''
    if text.count(sub)!=1:raise RuntimeError('GL4ES subimage anchor not found')
    text=text.replace(sub,sub+'''
        if(ttr_gl_log_enabled()) { LOAD_GLES(glGetError); GLenum ttr_err=gles_glGetError();
            ttr_gl_log("texsub tex=%u lvl=%d at %d,%d %dx%d fmt=0x%x max=%d base=%d err=0x%x", bound->texture, level, xoffset, yoffset, width, height, format, bound->max_level, bound->base_level, ttr_err); }''')
    text=text.replace('void APIENTRY_GL4ES gl4es_glTexImage2D(GLenum target,','int ttr_gl_log_enabled(void);\nvoid ttr_gl_log(const char *format, ...);\nvoid APIENTRY_GL4ES gl4es_glTexImage2D(GLenum target,',1)
    p.write_text(text)
    p=copy/'src/CMakeLists.txt';text=p.read_text()
    if 'ttr_present.c' not in text:
        p.write_text(text.replace('${CMAKE_CURRENT_SOURCE_DIR}/glx/glx.c','${CMAKE_CURRENT_SOURCE_DIR}/glx/glx.c\n        ${CMAKE_CURRENT_SOURCE_DIR}/glx/ttr_present.c',1))
    ndk=TOOLS/'android-ndk/android-ndk-r28c'
    run('cmake','-S',copy,'-B',build/'native','-G','Ninja','-DCMAKE_TOOLCHAIN_FILE='+(ndk/'build/cmake/android.toolchain.cmake').as_posix(),
        '-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-29','-DCMAKE_BUILD_TYPE=Release','-DDEFAULT_ES=2','-DNOX11=OFF','-DDEFAULT_FB=3','-DCMAKE_POLICY_VERSION_MINIMUM=3.5')
    run('cmake','--build',build/'native','--target','GL','-j','6')


if __name__=='__main__':main()
