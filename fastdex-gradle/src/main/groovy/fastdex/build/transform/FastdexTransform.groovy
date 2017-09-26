package fastdex.build.transform

import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInvocation
import fastdex.build.util.ClassInject
import fastdex.build.util.Constants
import fastdex.build.util.DexOperation
import fastdex.build.util.FastdexUtils
import fastdex.build.util.GradleUtils
import fastdex.build.variant.FastdexVariant
import com.google.common.collect.Lists
import fastdex.common.utils.SerializeUtils
import org.gradle.api.Project
import fastdex.common.utils.FileUtils
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.TransformInput
import fastdex.build.util.JarOperation

/**
 * 用于dex生成
 * 全量打包时的流程:
 * 1、合并所有的class文件生成一个jar包
 * 2、扫描所有的项目代码并且在构造方法里添加对com.dx168.fastdex.runtime.antilazyload.AntilazyLoad类的依赖
 *    这样做的目的是为了解决class verify的问题，
 *    详情请看https://mp.weixin.qq.com/s?__biz=MzI1MTA1MzM2Nw==&mid=400118620&idx=1&sn=b4fdd5055731290eef12ad0d17f39d4a
 * 3、对项目代码做快照，为了以后补丁打包时对比那些java文件发生了变化
 * 4、对当前项目的所以依赖做快照，为了以后补丁打包时对比依赖是否发生了变化，如果变化需要清除缓存
 * 5、调用真正的transform生成dex
 * 6、缓存生成的dex，并且把fastdex-runtime.dex插入到dex列表中，假如生成了两个dex，classes.dex classes2.dex 需要做一下操作
 *    fastdex-runtime.dex => classes.dex
 *    classes.dex         => classes2.dex
 *    classes2.dex        => classes3.dex
 *    然后运行期在入口Application(com.dx168.fastdex.runtime.FastdexApplication)使用MultiDex把所有的dex加载进来
 * 7、保存资源映射映射表，为了保持id的值一致，详情看
 * @see fastdex.build.task.FastdexResourceIdTask
 *
 * 补丁打包时的流程
 * 1、检查缓存的有效性
 * @see fastdex.build.task.FastdexCustomJavacTask 的prepareEnv方法说明
 * 2、扫描所有变化的java文件并编译成class
 * @see fastdex.build.task.FastdexCustomJavacTask
 * 3、合并所有变化的class并生成jar包
 * 4、生成补丁dex
 * 5、把所有的dex按照一定规律放在transformClassesWithMultidexlistFor${variantName}任务的输出目录
 *    fastdex-runtime.dex    => classes.dex
 *    patch.dex              => classes2.dex
 *    dex_cache.classes.dex  => classes3.dex
 *    dex_cache.classes2.dex => classes4.dex
 *    dex_cache.classesN.dex => classes(N + 2).dex
 *
 * Created by tong on 17/10/3.
 */
class FastdexTransform extends TransformProxy {
    FastdexVariant fastdexVariant

    Project project
    String variantName

    FastdexTransform(Transform base, FastdexVariant fastdexVariant) {
        super(base)
        this.fastdexVariant = fastdexVariant
        this.project = fastdexVariant.project
        this.variantName = fastdexVariant.variantName
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, IOException, InterruptedException {
        boolean useBuildCache = GradleUtils.ANDROID_GRADLE_PLUGIN_VERSION.compareTo("2.3") >= 0 && !fastdexVariant.hasJarMergingTask
        if (fastdexVariant.hasDexCache) {
            project.logger.error("==fastdex patch transform start,we will generate dex file")
            if (fastdexVariant.projectSnapshoot.diffResultSet.isJavaFileChanged()) {
                //生成补丁jar包
                File patchJar = generatePatchJar(transformInvocation)
                File patchDex = FastdexUtils.getPatchDexFile(fastdexVariant.project,fastdexVariant.variantName)
                DexOperation.generatePatchDex(fastdexVariant,base,patchJar,patchDex)
                fastdexVariant.metaInfo.patchDexVersion += 1

                //获取dex输出路径
                File dexOutputDir = GradleUtils.getDexOutputDir(project,base,transformInvocation,useBuildCache)
                //merged dex
                File mergedPatchDexDir = FastdexUtils.getMergedPatchDexDir(fastdexVariant.project,fastdexVariant.variantName)

                boolean willExecDexMerge = fastdexVariant.willExecDexMerge()
                boolean firstMergeDex = fastdexVariant.metaInfo.mergedDexVersion == 0

                if (willExecDexMerge) {
                    //merge dex
                    if (firstMergeDex) {
                        //copy 一份相同的，做冗余操作，如果直接移动文件，会丢失patch.dex造成免安装模块特别难处理
                        FileUtils.copyFileUsingStream(patchDex,new File(mergedPatchDexDir,Constants.CLASSES_DEX))
                        //第一只执行dex merge,直接保存patchDex
                        //patch.dex              => classes.dex
                        //dex_cache.classes.dex  => classes2.dex
                        //dex_cache.classes2.dex => classes3.dex
                        //dex_cache.classesN.dex => classes(N + 1).dex
                        //复制补丁dex到输出路径
                        hookPatchBuildDex(dexOutputDir,useBuildCache,willExecDexMerge)
                    }
                    else {
                        //已经执行过一次dex merge
                        File mergedPatchDex = new File(mergedPatchDexDir,Constants.CLASSES_DEX)
                        //更新patch.dex
                        DexOperation.mergeDex(fastdexVariant,mergedPatchDex,patchDex,mergedPatchDex)
                        hookPatchBuildDex(dexOutputDir, useBuildCache,willExecDexMerge)
                    }
                    fastdexVariant.metaInfo.mergedDexVersion += 1
                    fastdexVariant.metaInfo.save(fastdexVariant)
                    fastdexVariant.onDexGenerateSuccess(false,true)
                }
                else {
                    fastdexVariant.metaInfo.save(fastdexVariant)
                    //复制补丁打包的dex到输出路径
                    hookPatchBuildDex(dexOutputDir, useBuildCache,willExecDexMerge)
                    fastdexVariant.onDexGenerateSuccess(false,false)
                }
            }
            else {
                project.logger.error("==fastdex no java files have changed, just ignore")
            }
        }
        else {
            if (!fastdexVariant.hasJarMergingTask) {
                //所有输入的jar
                Set<String> jarInputFiles = new HashSet<>();
                for (TransformInput input : transformInvocation.getInputs()) {
                    Collection<JarInput> jarInputs = input.getJarInputs()
                    if (jarInputs != null) {
                        for (JarInput jarInput : jarInputs) {
                            jarInputFiles.add(jarInput.getFile().absolutePath)
                        }
                    }
                }
                File classpathFile = new File(FastdexUtils.getBuildDir(project,variantName),Constants.CLASSPATH_FILENAME)
                SerializeUtils.serializeTo(classpathFile,jarInputFiles)
            }

            project.logger.error("==fastdex normal transform start")

            if (useBuildCache) {
                ClassInject.injectTransformInvocation(fastdexVariant,transformInvocation)
            }
            else {
                def config = fastdexVariant.getVariantConfiguration()
                boolean isMultiDexEnabled = config.isMultiDexEnabled()

                if (isMultiDexEnabled) {
                    if (!fastdexVariant.executedJarMerge) {
                        ClassInject.injectTransformInvocation(fastdexVariant,transformInvocation)
                        File injectedJar = FastdexUtils.getInjectedJarFile(project,variantName)
                        GradleUtils.executeMerge(project,transformInvocation,injectedJar)
                        transformInvocation = GradleUtils.createNewTransformInvocation(base,transformInvocation,injectedJar)
                    }
                }
                else {
                    //如果没有开启multidex需要在此处做注入
                    ClassInject.injectTransformInvocation(fastdexVariant,transformInvocation)
                    if (fastdexVariant.configuration.useCustomCompile) {
                        File injectedJar = FastdexUtils.getInjectedJarFile(project,variantName)
                        GradleUtils.executeMerge(project,transformInvocation,injectedJar)
                    }
                }
            }
            //调用默认转换方法
            base.transform(transformInvocation)
            //获取dex输出路径
            File dexOutputDir = GradleUtils.getDexOutputDir(project,base,transformInvocation,useBuildCache)
            project.logger.error("==fastdex dexOutputDir: ${dexOutputDir}")

            //缓存dex
            int dexCount = cacheNormalBuildDex(dexOutputDir)

            //复制全量打包的dex到输出路径
            hookNormalBuildDex(dexOutputDir,useBuildCache)

            fastdexVariant.metaInfo.dexCount = dexCount
            fastdexVariant.metaInfo.buildMillis = System.currentTimeMillis()
            fastdexVariant.metaInfo.useBuildCache = useBuildCache

            fastdexVariant.onDexGenerateSuccess(true,false)
            project.logger.error("==fastdex normal transform end")
        }

        fastdexVariant.executedDexTransform = true
    }

    public void copyFastdexRuntimeDex(File dist) {
        File buildDir = FastdexUtils.getBuildDir(project)

        File fastdexRuntimeDex = new File(buildDir, Constants.RUNTIME_DEX_FILENAME)
        if (!FileUtils.isLegalFile(fastdexRuntimeDex)) {
            FileUtils.copyResourceUsingStream(Constants.RUNTIME_DEX_FILENAME, fastdexRuntimeDex)
        }
        FileUtils.copyFileUsingStream(fastdexRuntimeDex, dist)

        project.logger.error("==fastdex fastdex-runtime.dex => " + dist)
    }

    /**
     * 获取输出jar路径
     * @param invocation
     * @return
     */
    public File getCombinedJarFile(TransformInvocation invocation) {
        List<JarInput> jarInputs = Lists.newArrayList();
        for (TransformInput input : invocation.getInputs()) {
            jarInputs.addAll(input.getJarInputs());
        }
        if (jarInputs.size() != 1) {
            throw new RuntimeException("==fastdex jar input size is ${jarInputs.size()}, expected is 1")
        }
        File combinedJar = jarInputs.get(0).getFile()
        return combinedJar
    }

    /**
     * 生成补丁jar包
     * @param transformInvocation
     * @return
     */
    File generatePatchJar(TransformInvocation transformInvocation) {
        def config = fastdexVariant.androidVariant.getVariantData().getVariantConfiguration()
        if (fastdexVariant.hasJarMergingTask) {
            //如果开启了multidex,FastdexJarMergingTransform完成了jar merge的操作
            File patchJar = getCombinedJarFile(transformInvocation)
            project.logger.error("==fastdex multiDex enabled use patch.jar: ${patchJar}")
            return patchJar
        }
        else {
            //补丁jar
            File patchJar = new File(FastdexUtils.getBuildDir(project,variantName),"patch-combined.jar")
            //生成补丁jar
            JarOperation.generatePatchJar(fastdexVariant,transformInvocation,patchJar)
            return patchJar
        }
    }

    /**
     * 缓存全量打包时生成的dex
     * @param dexOutputDir dex输出路径
     */
    int cacheNormalBuildDex(File dexOutputDir) {
        project.logger.error("==fastdex dex output directory: " + dexOutputDir)
        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)
        return FileUtils.copyDir(dexOutputDir,cacheDexDir,Constants.DEX_SUFFIX)
    }

    /**
     * 全量打包时复制dex到指定位置
     * @param dexOutputDir dex输出路径
     */
    void hookNormalBuildDex(File dexOutputDir, boolean useBuildCache) {
        if (useBuildCache) {
            dexOutputDir = FastdexUtils.mergeDexOutputDir(dexOutputDir)
        }
        else {
            //dexelements [fastdex-runtime.dex ${dex_cache}.listFiles]
            //runtime.dex            => classes.dex
            //dex_cache.classes.dex  => classes2.dex
            //dex_cache.classes2.dex => classes3.dex
            //dex_cache.classesN.dex => classes(N + 1).dex

            FastdexUtils.incrementDexDir(dexOutputDir)
        }

        //fastdex-runtime.dex = > classes.dex
        copyFastdexRuntimeDex(new File(dexOutputDir,Constants.CLASSES_DEX))
        printLogWhenDexGenerateComplete(dexOutputDir,true)
    }

    /**
     * 补丁打包时复制dex到指定位置
     * @param dexOutputDir dex输出路径
     */
    void hookPatchBuildDex(File dexOutputDir, boolean useBuildCache,boolean willExecDexMerge) {
        //dexelements [fastdex-runtime.dex patch.dex ${dex_cache}.listFiles]
        //runtime.dex            => classes.dex
        //patch.dex              => classes2.dex
        //dex_cache.classes.dex  => classes3.dex
        //dex_cache.classes2.dex => classes4.dex
        //dex_cache.classesN.dex => classes(N + 2).dex

        project.logger.error(" ")
        project.logger.error("==fastdex patch transform hook patch dex start")

        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)

        File patchDex = FastdexUtils.getPatchDexFile(fastdexVariant.project,fastdexVariant.variantName)
        File mergedPatchDex = FastdexUtils.getMergedPatchDexFile(fastdexVariant.project,fastdexVariant.variantName)

        FileUtils.cleanDir(dexOutputDir)
        FileUtils.copyDir(cacheDexDir,dexOutputDir,Constants.DEX_SUFFIX)

        int dsize = 1
        //如果本次打包触发了dexmerge就不需要patch.dex了
        boolean copyPatchDex = !willExecDexMerge && FileUtils.isLegalFile(patchDex)
        if (copyPatchDex) {
            dsize += 1
        }
        boolean copyMergedPatchDex = FileUtils.isLegalFile(mergedPatchDex)
        if (copyMergedPatchDex) {
            dsize += 1
        }

        if (useBuildCache) {
            dexOutputDir = FastdexUtils.mergeDexOutputDir(dexOutputDir,dsize)
        }
        else {
            FastdexUtils.incrementDexDir(dexOutputDir,dsize)
        }

        printLogWhenDexGenerateComplete(dexOutputDir,false)
        //copy fastdex-runtime.dex
        copyFastdexRuntimeDex(new File(dexOutputDir,Constants.CLASSES_DEX))

        int point = 2
        if (copyPatchDex) {
            //copy patch.dex
            FileUtils.copyFileUsingStream(patchDex,new File(dexOutputDir,"classes${point}.dex"))
            point += 1

            project.logger.error("==fastdex patch.dex => " + new File(dexOutputDir,"classes${point}.dex"))
        }
        if (copyMergedPatchDex) {
            //copy merged-patch.dex
            FileUtils.copyFileUsingStream(mergedPatchDex,new File(dexOutputDir,"classes${point}.dex"))

            project.logger.error("==fastdex merged-patch.dex => " + new File(dexOutputDir,"classes${point}.dex"))
        }
        printLogWhenDexGenerateComplete(dexOutputDir,false)
        project.logger.error(" ")
    }

    /**
     * 当dex生成完成后打印日志
     * @param normalBuild
     */
    void printLogWhenDexGenerateComplete(File dexOutputDir,boolean normalBuild) {
        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)

        //log
        StringBuilder sb = new StringBuilder()
        sb.append("cached_dex[")
        File[] dexFiles = cacheDexDir.listFiles()
        for (File file : dexFiles) {
            if (file.getName().endsWith(Constants.DEX_SUFFIX)) {
                sb.append(file.getName())
                if (file != dexFiles[dexFiles.length - 1]) {
                    sb.append(",")
                }
            }
        }
        sb.append("] cur-dex[")
        dexFiles = dexOutputDir.listFiles()
        int idx = 0
        for (File file : dexFiles) {
            if (file.getName().endsWith(Constants.DEX_SUFFIX)) {
                sb.append(file.getName())
                if (idx < (dexFiles.length - 1)) {
                    sb.append(",")
                }
            }
            idx ++
        }
        sb.append("]")
        if (normalBuild) {
            project.logger.error("==fastdex first build ${sb}")
        }
        else {
            project.logger.error("==fastdex patch build ${sb}")
        }
    }
}