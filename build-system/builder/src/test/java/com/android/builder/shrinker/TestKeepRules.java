/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.builder.shrinker;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Simple {@link KeepRules} implementation for testing.
 */
class TestKeepRules implements KeepRules {
    private final String mClassName;
    private final Set<String> mMethodNames;

    TestKeepRules(String className, String... methodNames) {
        mClassName = className;
        mMethodNames = ImmutableSet.copyOf(methodNames);
    }

    @Override
    public <T> Set<T> getSymbolsToKeep(T klass, ShrinkerGraph<T> graph) {
        Set<T> symbols = Sets.newHashSet();

        if (graph.getClassName(klass).endsWith(mClassName)) {
            for (T member : graph.getMethods(klass)) {
                String name = graph.getClassName(member);
                for (String methodName : mMethodNames) {
                    if (name.contains("." + methodName + ":")) {
                        symbols.add(member);
                    }
                }
            }
        }

        return symbols;
    }
}
