/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.ndk.internal

import com.android.build.gradle.api.AndroidSourceDirectorySet
import com.android.build.gradle.ndk.NdkExtension
import com.android.build.gradle.tasks.GdbSetupTask
import com.android.builder.core.BuilderConstants
import com.android.builder.model.AndroidProject
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.language.base.ProjectSourceSet
import org.gradle.language.base.FunctionalSourceSet
import org.gradle.language.c.CSourceSet
import org.gradle.language.cpp.CppSourceSet
import org.gradle.api.Task
import org.gradle.api.tasks.Copy
import org.gradle.model.internal.core.ModelPath
import org.gradle.model.internal.core.ModelType
import org.gradle.nativeplatform.BuildTypeContainer
import org.gradle.nativeplatform.NativeLibrarySpec
import org.gradle.nativeplatform.internal.DefaultSharedLibraryBinarySpec
import org.gradle.language.c.tasks.CCompile
import org.gradle.language.cpp.tasks.CppCompile
import org.gradle.platform.base.ComponentSpecContainer

/**
 * Configure settings used by the native binaries.
 */
class NdkConfigurationAction implements Action<Project> {

    NdkExtension ndkExtension

    NdkHandler ndkHandler

    NdkConfigurationAction(NdkHandler ndkHandler, NdkExtension ndkExtension) {
        this.ndkExtension = ndkExtension
        this.ndkHandler = ndkHandler
    }

    public void execute(Project project) {
        createBuildTypes(
            project.modelRegistry.get(ModelPath.path("buildTypes"), ModelType.of(BuildTypeContainer)));

        NativeLibrarySpec library = createNativeLibrary(
            project.modelRegistry.get(ModelPath.path("componentSpecs"), ModelType.of(ComponentSpecContainer)),
            ndkExtension);

        configureProperties(
                library,
                project, ndkExtension, ndkHandler)

        configureSources(project.sources, ndkExtension)
    }

    public static void createBuildTypes(BuildTypeContainer buildTypes) {
        buildTypes.maybeCreate(BuilderConstants.DEBUG)
        buildTypes.maybeCreate(BuilderConstants.RELEASE)
    }

    public static NativeLibrarySpec createNativeLibrary(ComponentSpecContainer specs, NdkExtension extension) {
        NativeLibrarySpec library = specs.create(extension.getModuleName(), NativeLibrarySpec)
    }

    public static void configureSources(ProjectSourceSet sources, NdkExtension ndkExtension) {
        String moduleName = ndkExtension.getModuleName();
        ndkExtension.getSourceSets().all { AndroidSourceDirectorySet sourceSet ->
            // For Android's main source set, just configure the default FunctionalSourceSet.
            String sourceSetName = sourceSet.name

            sources.maybeCreate(sourceSetName).configure {
                c(CSourceSet) {
                    source {
                        if (srcDirs.isEmpty()) {
                            srcDir "src/$sourceSetName/jni"
                        }
                        if (includes.isEmpty()) {
                            include ndkExtension.getCFilePattern().getIncludes()
                            exclude ndkExtension.getCFilePattern().getExcludes()
                        }
                    }
                }
                cpp(CppSourceSet) {
                    source {
                        if (srcDirs.isEmpty()) {
                            srcDir "src/$sourceSetName/jni"
                        }
                        if (includes.isEmpty()) {
                            include ndkExtension.getCppFilePattern().getIncludes()
                            exclude ndkExtension.getCppFilePattern().getExcludes()
                        }
                    }
                }
            }
        }
    }

    public static void configureProperties(NativeLibrarySpec library, Project project, NdkExtension ndkExtension, NdkHandler ndkHandler) {
        Collection<String> abiList = ndkHandler.getSupportedAbis()
        library.targetPlatform(abiList.toArray(new String[abiList.size()]))

        library.binaries.withType(DefaultSharedLibraryBinarySpec) { DefaultSharedLibraryBinarySpec binary ->
            if (binary.targetPlatform.name.equals("current")) {
                return
            }

            // TODO: Support flavorDimension.
            sourceIfExist(binary, project.sources, "main")
            sourceIfExist(binary, project.sources, "${flavor.name}")
            sourceIfExist(binary, project.sources, "${buildType.name}")

            cCompiler.define "ANDROID"
            cppCompiler.define "ANDROID"
            cCompiler.define "ANDROID_NDK"
            cppCompiler.define "ANDROID_NDK"

            // Set output library filename.
            sharedLibraryFile = new File(
                    project.buildDir,
                    NdkNamingScheme.getOutputDirectoryName(binary) + "/" +
                            NdkNamingScheme.getSharedLibraryFileName(ndkExtension.getModuleName()))

            // Replace output directory of compile tasks.
            binary.tasks.withType(CCompile) {
                String sourceSetName = objectFileDir.name
                objectFileDir = project.file(
                        "$project.buildDir/$AndroidProject.FD_INTERMEDIATES/objectFiles/" +
                                "${binary.namingScheme.outputDirectoryBase}/$sourceSetName")
            }
            binary.tasks.withType(CppCompile) {
                String sourceSetName = objectFileDir.name
                objectFileDir = project.file(
                        "$project.buildDir/$AndroidProject.FD_INTERMEDIATES/objectFiles/" +
                                "${binary.namingScheme.outputDirectoryBase}/$sourceSetName")
            }

            String sysroot = ndkHandler.getSysroot(binary.targetPlatform)
            cCompiler.args  "--sysroot=$sysroot"
            cppCompiler.args  "--sysroot=$sysroot"
            linker.args "--sysroot=$sysroot"

            if (ndkExtension.getRenderscriptNdkMode()) {
                cCompiler.args "-I$sysroot/usr/include/rs"
                cCompiler.args "-I$sysroot/usr/include/rs/cpp"
                cppCompiler.args "-I$sysroot/usr/include/rs"
                cppCompiler.args "-I$sysroot/usr/include/rs/cpp"
                linker.args "-L$sysroot/usr/lib/rs"
            }

            StlConfiguration.apply(ndkHandler, ndkExtension.getStl(), project, binary)

            NativeToolSpecificationFactory.create(ndkHandler, binary.buildType, binary.targetPlatform).apply(binary)

            // Add flags defined in NdkExtension
            if (ndkExtension.getcFlags() != null) {
                cCompiler.args ndkExtension.getcFlags()
            }
            if (ndkExtension.getCppFlags() != null) {
                cppCompiler.args ndkExtension.getCppFlags()
            }
            for (String ldLibs : ndkExtension.getLdLibs()) {
                linker.args "-l$ldLibs"
            }

            if (buildType.name.equals(BuilderConstants.DEBUG)) {
                setupNdkGdbDebug(project, binary, ndkExtension, ndkHandler)
            }
        }
    }

    /**
     * Add the sourceSet with the specified name to the binary if such sourceSet is defined.
     */
    private static void sourceIfExist(
            DefaultSharedLibraryBinarySpec binary,
            ProjectSourceSet projectSourceSet,
            String sourceSetName) {
        FunctionalSourceSet sourceSet = projectSourceSet.findByName(sourceSetName)
        if (sourceSet != null) {
            binary.source(sourceSet)
        }
    }

    /**
     * Setup tasks to create gdb.setup and copy gdbserver for NDK debugging.
     */
    private static void setupNdkGdbDebug(Project project, DefaultSharedLibraryBinarySpec binary, NdkExtension ndkExtension, NdkHandler handler) {
        Task copyGdbServerTask = project.tasks.create(
                name: binary.namingScheme.getTaskName("copy", "GdbServer"),
                type: Copy) {
            from(new File(
                    handler.getPrebuiltDirectory(binary.targetPlatform),
                    "gdbserver/gdbserver"))
            into(new File(project.buildDir, NdkNamingScheme.getOutputDirectoryName(binary)))
        }
        binary.builtBy copyGdbServerTask

        Task createGdbSetupTask = project.tasks.create(
                name: binary.namingScheme.getTaskName("create", "Gdbsetup"),
                type: GdbSetupTask) { def task ->
            task.ndkHandler = handler
            task.extension = ndkExtension
            task.binary = binary
            task.outputDir = new File(project.buildDir, NdkNamingScheme.getOutputDirectoryName(binary))
        }
        binary.builtBy createGdbSetupTask
    }
}
