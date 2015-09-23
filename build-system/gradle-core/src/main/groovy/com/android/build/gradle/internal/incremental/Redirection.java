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

package com.android.build.gradle.internal.incremental;

import com.android.annotations.NonNull;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.List;

/**
 * A redirection is the part of an instrumented method that calls out to a different implementation.
 */
public abstract class Redirection {
    /**
     * The name of the method we redirect to.
     */
    @NonNull
    private final String name;

    Redirection(@NonNull String name) {
        this.name = name;
    }

    /**
     * Adds the instructions to do a generic redirection.
     *
     * @param mv the method visitor to add the instructions to.
     * @param change the local variable containing the alternate implementation.
     * @param args the type of the local variable that need to be forwarded.
     */
    void redirect(GeneratorAdapter mv, int change, List<Type> args) {
        // code to check if a new implementation of the current class is available.
        Label l0 = new Label();
        mv.loadLocal(change);
        mv.visitJumpInsn(Opcodes.IFNULL, l0);
        mv.loadLocal(change);
        mv.push(name);

        // create an array of objects capable of containing all the parameters and optionally the "this"
        createLocals(mv, args);

        // we need to maintain the stack index when loading parameters from, as for long and double
        // values, it uses 2 stack elements, all others use only 1 stack element.
        int stackIndex = 0;
        for (int arrayIndex = 0; arrayIndex < args.size(); arrayIndex++) {
            Type arg = args.get(arrayIndex);
            // duplicate the array of objects reference, it will be used to store the value in.
            mv.dup();
            // index in the array of objects to store the boxed parameter.
            mv.push(arrayIndex);
            // Pushes the appropriate local variable on the stack
            redirectLocal(mv, stackIndex, arg);
            // potentially box up intrinsic types.
            mv.box(arg);
            mv.arrayStore(Type.getType(Object.class));
            // stack index must progress according to the parameter type we just processed.
            stackIndex += arg.getSize();
        }

        // now invoke the generic dispatch method.
        mv.invokeInterface(IncrementalVisitor.CHANGE_TYPE,Method.getMethod("Object access$dispatch(String, Object[])"));

        // Restore the state after the redirection
        restore(mv, args);
        // jump label for classes without any new implementation, just invoke the original
        // method implementation.
        mv.visitLabel(l0);
    }

    /**
     * Creates and pushes to the stack the array to hold all the parameters to redirect, and
     * optionally this.
     */
    protected void createLocals(GeneratorAdapter mv, List<Type> args) {
        mv.push(args.size());
        mv.newArray(Type.getType(Object.class));
    }

    /**
     * After the redirection is called, this methods handles restoring the state given
     * the return values of the redirection.
     */
    protected abstract void restore(GeneratorAdapter mv, List<Type> args);

    /**
     * Pushes in the stack the value that should be redirected for the given local.
     */
    protected void redirectLocal(GeneratorAdapter mv, int local, Type arg) {
        mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), local);
    }
}
