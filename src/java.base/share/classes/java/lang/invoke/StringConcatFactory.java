/*
 * Copyright (c) 2015, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.vm.annotation.Stable;
import sun.invoke.util.Wrapper;

import java.lang.invoke.MethodHandles.Lookup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static java.lang.invoke.MethodType.methodType;

/**
 * <p>Methods to facilitate the creation of String concatenation methods, that
 * can be used to efficiently concatenate a known number of arguments of known
 * types, possibly after type adaptation and partial evaluation of arguments.
 * These methods are typically used as <em>bootstrap methods</em> for {@code
 * invokedynamic} call sites, to support the <em>string concatenation</em>
 * feature of the Java Programming Language.
 *
 * <p>Indirect access to the behavior specified by the provided {@code
 * MethodHandle} proceeds in order through two phases:
 *
 * <ol>
 *     <li><em>Linkage</em> occurs when the methods in this class are invoked.
 * They take as arguments a method type describing the concatenated arguments
 * count and types, and optionally the String <em>recipe</em>, plus the
 * constants that participate in the String concatenation. The details on
 * accepted recipe shapes are described further below. Linkage may involve
 * dynamically loading a new class that implements the expected concatenation
 * behavior. The {@code CallSite} holds the {@code MethodHandle} pointing to the
 * exact concatenation method. The concatenation methods may be shared among
 * different {@code CallSite}s, e.g. if linkage methods produce them as pure
 * functions.</li>
 *
 * <li><em>Invocation</em> occurs when a generated concatenation method is
 * invoked with the exact dynamic arguments. This may occur many times for a
 * single concatenation method. The method referenced by the behavior {@code
 * MethodHandle} is invoked with the static arguments and any additional dynamic
 * arguments provided on invocation, as if by {@link MethodHandle#invoke(Object...)}.</li>
 * </ol>
 *
 * <p> This class provides two forms of linkage methods: a simple version
 * ({@link #makeConcat(java.lang.invoke.MethodHandles.Lookup, String,
 * MethodType)}) using only the dynamic arguments, and an advanced version
 * ({@link #makeConcatWithConstants(java.lang.invoke.MethodHandles.Lookup,
 * String, MethodType, String, Object...)} using the advanced forms of capturing
 * the constant arguments. The advanced strategy can produce marginally better
 * invocation bytecode, at the expense of exploding the number of shapes of
 * string concatenation methods present at runtime, because those shapes would
 * include constant static arguments as well.
 *
 * @author Aleksey Shipilev
 * @author Remi Forax
 * @author Peter Levart
 *
 * @apiNote
 * <p>There is a JVM limit (classfile structural constraint): no method
 * can call with more than 255 slots. This limits the number of static and
 * dynamic arguments one can pass to bootstrap method. Since there are potential
 * concatenation strategies that use {@code MethodHandle} combinators, we need
 * to reserve a few empty slots on the parameter lists to capture the
 * temporal results. This is why bootstrap methods in this factory do not accept
 * more than 200 argument slots. Users requiring more than 200 argument slots in
 * concatenation are expected to split the large concatenation in smaller
 * expressions.
 *
 * @since 9
 */
public final class StringConcatFactory {

    /**
     * Tag used to demarcate an ordinary argument.
     */
    private static final char TAG_ARG = '\u0001';

    /**
     * Tag used to demarcate a constant.
     */
    private static final char TAG_CONST = '\u0002';

    /**
     * Maximum number of argument slots in String Concat call.
     *
     * While the maximum number of argument slots that indy call can handle is 253,
     * we do not use all those slots, to let the strategies with MethodHandle
     * combinators to use some arguments.
     */
    private static final int MAX_INDY_CONCAT_ARG_SLOTS = 200;

    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    /**
     * Parses the recipe string, and produces a traversable collection of
     * {@link java.lang.invoke.StringConcatFactory.RecipeElement}-s for generator
     * strategies. Notably, this class parses out the constants from the recipe
     * and from other static arguments.
     */
    private static final class Recipe {
        private final List<RecipeElement> elements;

        public Recipe(String src, Object[] constants) {
            List<RecipeElement> el = new ArrayList<>();

            int constC = 0;
            int argC = 0;

            StringBuilder acc = new StringBuilder();

            for (int i = 0; i < src.length(); i++) {
                char c = src.charAt(i);

                if (c == TAG_CONST || c == TAG_ARG) {
                    // Detected a special tag, flush all accumulated characters
                    // as a constant first:
                    if (acc.length() > 0) {
                        el.add(new RecipeElement(acc.toString()));
                        acc.setLength(0);
                    }
                    if (c == TAG_CONST) {
                        Object cnst = constants[constC++];
                        el.add(new RecipeElement(cnst));
                    } else if (c == TAG_ARG) {
                        el.add(new RecipeElement(argC++));
                    }
                } else {
                    // Not a special character, this is a constant embedded into
                    // the recipe itself.
                    acc.append(c);
                }
            }

            // Flush the remaining characters as constant:
            if (acc.length() > 0) {
                el.add(new RecipeElement(acc.toString()));
            }

            elements = el;
        }

        public List<RecipeElement> getElements() {
            return elements;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Recipe recipe = (Recipe) o;
            return elements.equals(recipe.elements);
        }

        @Override
        public String toString() {
            return "Recipe{" +
                    "elements=" + elements +
                    '}';
        }

        @Override
        public int hashCode() {
            return elements.hashCode();
        }
    }

    private static final class RecipeElement {
        private final String value;
        private final int argPos;
        private final char tag;

        public RecipeElement(Object cnst) {
            this.value = String.valueOf(Objects.requireNonNull(cnst));
            this.argPos = -1;
            this.tag = TAG_CONST;
        }

        public RecipeElement(int arg) {
            this.value = null;
            this.argPos = arg;
            this.tag = TAG_ARG;
        }

        public String getValue() {
            assert (tag == TAG_CONST);
            return value;
        }

        public int getArgPos() {
            assert (tag == TAG_ARG);
            return argPos;
        }

        public char getTag() {
            return tag;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RecipeElement that = (RecipeElement) o;

            if (this.tag != that.tag) return false;
            if (this.tag == TAG_CONST && (!value.equals(that.value))) return false;
            if (this.tag == TAG_ARG && (argPos != that.argPos)) return false;
            return true;
        }

        @Override
        public String toString() {
            return "RecipeElement{" +
                    "value='" + value + '\'' +
                    ", argPos=" + argPos +
                    ", tag=" + tag +
                    '}';
        }

        @Override
        public int hashCode() {
            return (int)tag;
        }
    }

    // StringConcatFactory bootstrap methods are startup sensitive, and may be
    // special cased in java.lang.invokeBootstrapMethodInvoker to ensure
    // methods are invoked with exact type information to avoid generating
    // code for runtime checks. Take care any changes or additions here are
    // reflected there as appropriate.

    /**
     * Facilitates the creation of optimized String concatenation methods, that
     * can be used to efficiently concatenate a known number of arguments of
     * known types, possibly after type adaptation and partial evaluation of
     * arguments. Typically used as a <em>bootstrap method</em> for {@code
     * invokedynamic} call sites, to support the <em>string concatenation</em>
     * feature of the Java Programming Language.
     *
     * <p>When the target of the {@code CallSite} returned from this method is
     * invoked, it returns the result of String concatenation, taking all
     * function arguments passed to the linkage method as inputs for
     * concatenation. The target signature is given by {@code concatType}.
     * For a target accepting:
     * <ul>
     *     <li>zero inputs, concatenation results in an empty string;</li>
     *     <li>one input, concatenation results in the single
     *     input converted as per JLS 5.1.11 "String Conversion"; otherwise</li>
     *     <li>two or more inputs, the inputs are concatenated as per
     *     requirements stated in JLS 15.18.1 "String Concatenation Operator +".
     *     The inputs are converted as per JLS 5.1.11 "String Conversion",
     *     and combined from left to right.</li>
     * </ul>
     *
     * <p>Assume the linkage arguments are as follows:
     *
     * <ul>
     *     <li>{@code concatType}, describing the {@code CallSite} signature</li>
     * </ul>
     *
     * <p>Then the following linkage invariants must hold:
     *
     * <ul>
     *     <li>The number of parameter slots in {@code concatType} is
     *         less than or equal to 200</li>
     *     <li>The return type in {@code concatType} is assignable from {@link java.lang.String}</li>
     * </ul>
     *
     * @param lookup   Represents a lookup context with the accessibility
     *                 privileges of the caller. Specifically, the lookup
     *                 context must have
     *                 {@linkplain MethodHandles.Lookup#hasFullPrivilegeAccess()
     *                 full privilege access}.
     *                 When used with {@code invokedynamic}, this is stacked
     *                 automatically by the VM.
     * @param name     The name of the method to implement. This name is
     *                 arbitrary, and has no meaning for this linkage method.
     *                 When used with {@code invokedynamic}, this is provided by
     *                 the {@code NameAndType} of the {@code InvokeDynamic}
     *                 structure and is stacked automatically by the VM.
     * @param concatType The expected signature of the {@code CallSite}.  The
     *                   parameter types represent the types of concatenation
     *                   arguments; the return type is always assignable from {@link
     *                   java.lang.String}.  When used with {@code invokedynamic},
     *                   this is provided by the {@code NameAndType} of the {@code
     *                   InvokeDynamic} structure and is stacked automatically by
     *                   the VM.
     * @return a CallSite whose target can be used to perform String
     * concatenation, with dynamic concatenation arguments described by the given
     * {@code concatType}.
     * @throws StringConcatException If any of the linkage invariants described
     *                               here are violated, or the lookup context
     *                               does not have private access privileges.
     * @throws NullPointerException If any of the incoming arguments is null.
     *                              This will never happen when a bootstrap method
     *                              is called with invokedynamic.
     *
     * @jls  5.1.11 String Conversion
     * @jls 15.18.1 String Concatenation Operator +
     */
    public static CallSite makeConcat(MethodHandles.Lookup lookup,
                                      String name,
                                      MethodType concatType) throws StringConcatException {
        return doStringConcat(lookup, name, concatType, true, null);
    }

    /**
     * Facilitates the creation of optimized String concatenation methods, that
     * can be used to efficiently concatenate a known number of arguments of
     * known types, possibly after type adaptation and partial evaluation of
     * arguments. Typically used as a <em>bootstrap method</em> for {@code
     * invokedynamic} call sites, to support the <em>string concatenation</em>
     * feature of the Java Programming Language.
     *
     * <p>When the target of the {@code CallSite} returned from this method is
     * invoked, it returns the result of String concatenation, taking all
     * function arguments and constants passed to the linkage method as inputs for
     * concatenation. The target signature is given by {@code concatType}, and
     * does not include constants.
     * For a target accepting:
     * <ul>
     *     <li>zero inputs, concatenation results in an empty string;</li>
     *     <li>one input, concatenation results in the single
     *     input converted as per JLS 5.1.11 "String Conversion"; otherwise</li>
     *     <li>two or more inputs, the inputs are concatenated as per
     *     requirements stated in JLS 15.18.1 "String Concatenation Operator +".
     *     The inputs are converted as per JLS 5.1.11 "String Conversion",
     *     and combined from left to right.</li>
     * </ul>
     *
     * <p>The concatenation <em>recipe</em> is a String description for the way to
     * construct a concatenated String from the arguments and constants. The
     * recipe is processed from left to right, and each character represents an
     * input to concatenation. Recipe characters mean:
     *
     * <ul>
     *
     *   <li><em>\1 (Unicode point 0001)</em>: an ordinary argument. This
     *   input is passed through dynamic argument, and is provided during the
     *   concatenation method invocation. This input can be null.</li>
     *
     *   <li><em>\2 (Unicode point 0002):</em> a constant. This input passed
     *   through static bootstrap argument. This constant can be any value
     *   representable in constant pool. If necessary, the factory would call
     *   {@code toString} to perform a one-time String conversion.</li>
     *
     *   <li><em>Any other char value:</em> a single character constant.</li>
     * </ul>
     *
     * <p>Assume the linkage arguments are as follows:
     *
     * <ul>
     *   <li>{@code concatType}, describing the {@code CallSite} signature</li>
     *   <li>{@code recipe}, describing the String recipe</li>
     *   <li>{@code constants}, the vararg array of constants</li>
     * </ul>
     *
     * <p>Then the following linkage invariants must hold:
     *
     * <ul>
     *   <li>The number of parameter slots in {@code concatType} is less than
     *       or equal to 200</li>
     *
     *   <li>The parameter count in {@code concatType} is equal to number of \1 tags
     *   in {@code recipe}</li>
     *
     *   <li>The return type in {@code concatType} is assignable
     *   from {@link java.lang.String}, and matches the return type of the
     *   returned {@link MethodHandle}</li>
     *
     *   <li>The number of elements in {@code constants} is equal to number of \2
     *   tags in {@code recipe}</li>
     * </ul>
     *
     * @param lookup    Represents a lookup context with the accessibility
     *                  privileges of the caller. Specifically, the lookup
     *                  context must have
     *                  {@linkplain MethodHandles.Lookup#hasFullPrivilegeAccess()
     *                  full privilege access}.
     *                  When used with {@code invokedynamic}, this is stacked
     *                  automatically by the VM.
     * @param name      The name of the method to implement. This name is
     *                  arbitrary, and has no meaning for this linkage method.
     *                  When used with {@code invokedynamic}, this is provided
     *                  by the {@code NameAndType} of the {@code InvokeDynamic}
     *                  structure and is stacked automatically by the VM.
     * @param concatType The expected signature of the {@code CallSite}.  The
     *                  parameter types represent the types of dynamic concatenation
     *                  arguments; the return type is always assignable from {@link
     *                  java.lang.String}.  When used with {@code
     *                  invokedynamic}, this is provided by the {@code
     *                  NameAndType} of the {@code InvokeDynamic} structure and
     *                  is stacked automatically by the VM.
     * @param recipe    Concatenation recipe, described above.
     * @param constants A vararg parameter representing the constants passed to
     *                  the linkage method.
     * @return a CallSite whose target can be used to perform String
     * concatenation, with dynamic concatenation arguments described by the given
     * {@code concatType}.
     * @throws StringConcatException If any of the linkage invariants described
     *                               here are violated, or the lookup context
     *                               does not have private access privileges.
     * @throws NullPointerException If any of the incoming arguments is null, or
     *                              any constant in {@code recipe} is null.
     *                              This will never happen when a bootstrap method
     *                              is called with invokedynamic.
     * @apiNote Code generators have three distinct ways to process a constant
     * string operand S in a string concatenation expression.  First, S can be
     * materialized as a reference (using ldc) and passed as an ordinary argument
     * (recipe '\1'). Or, S can be stored in the constant pool and passed as a
     * constant (recipe '\2') . Finally, if S contains neither of the recipe
     * tag characters ('\1', '\2') then S can be interpolated into the recipe
     * itself, causing its characters to be inserted into the result.
     *
     * @jls  5.1.11 String Conversion
     * @jls 15.18.1 String Concatenation Operator +
     */
    public static CallSite makeConcatWithConstants(MethodHandles.Lookup lookup,
                                                   String name,
                                                   MethodType concatType,
                                                   String recipe,
                                                   Object... constants) throws StringConcatException {
        return doStringConcat(lookup, name, concatType, false, recipe, constants);
    }

    private static CallSite doStringConcat(MethodHandles.Lookup lookup,
                                           String name,
                                           MethodType concatType,
                                           boolean generateRecipe,
                                           String recipe,
                                           Object... constants) throws StringConcatException {
        Objects.requireNonNull(lookup, "Lookup is null");
        Objects.requireNonNull(name, "Name is null");
        Objects.requireNonNull(concatType, "Concat type is null");
        Objects.requireNonNull(constants, "Constants are null");

        for (Object o : constants) {
            Objects.requireNonNull(o, "Cannot accept null constants");
        }

        if ((lookup.lookupModes() & MethodHandles.Lookup.PRIVATE) == 0) {
            throw new StringConcatException("Invalid caller: " +
                    lookup.lookupClass().getName());
        }

        int cCount = 0;
        int oCount = 0;
        if (generateRecipe) {
            // Mock the recipe to reuse the concat generator code
            char[] value = new char[concatType.parameterCount()];
            Arrays.fill(value, TAG_ARG);
            recipe = new String(value);
            oCount = concatType.parameterCount();
        } else {
            Objects.requireNonNull(recipe, "Recipe is null");

            for (int i = 0; i < recipe.length(); i++) {
                char c = recipe.charAt(i);
                if (c == TAG_CONST) cCount++;
                if (c == TAG_ARG)   oCount++;
            }
        }

        if (oCount != concatType.parameterCount()) {
            throw new StringConcatException(
                    "Mismatched number of concat arguments: recipe wants " +
                            oCount +
                            " arguments, but signature provides " +
                            concatType.parameterCount());
        }

        if (cCount != constants.length) {
            throw new StringConcatException(
                    "Mismatched number of concat constants: recipe wants " +
                            cCount +
                            " constants, but only " +
                            constants.length +
                            " are passed");
        }

        if (!concatType.returnType().isAssignableFrom(String.class)) {
            throw new StringConcatException(
                    "The return type should be compatible with String, but it is " +
                            concatType.returnType());
        }

        if (concatType.parameterSlotCount() > MAX_INDY_CONCAT_ARG_SLOTS) {
            throw new StringConcatException("Too many concat argument slots: " +
                    concatType.parameterSlotCount() +
                    ", can only accept " +
                    MAX_INDY_CONCAT_ARG_SLOTS);
        }

        Recipe rec = new Recipe(recipe, constants);
        MethodHandle mh = generate(lookup, concatType, rec);
        return new ConstantCallSite(mh.asType(concatType));
    }

    private static MethodHandle generate(Lookup lookup, MethodType mt, Recipe recipe) throws StringConcatException {
        try {
            return generateMHInlineCopy(mt, recipe);
        } catch (Error | StringConcatException e) {
            // Pass through any error or existing StringConcatException
            throw e;
        } catch (Throwable t) {
            throw new StringConcatException("Generator failed", t);
        }
    }


    /**
     * <p>This strategy replicates what StringBuilders are doing: it builds the
     * byte[] array on its own and passes that byte[] array to String
     * constructor. This strategy requires access to some private APIs in JDK,
     * most notably, the private String constructor that accepts byte[] arrays
     * without copying.
     */
    private static MethodHandle generateMHInlineCopy(MethodType mt, Recipe recipe) throws Throwable {

        // Fast-path two-argument Object + Object concatenations
        if (recipe.getElements().size() == 2) {
            // Two object arguments
            if (mt.parameterCount() == 2 &&
                    !mt.parameterType(0).isPrimitive() &&
                    !mt.parameterType(1).isPrimitive() &&
                    recipe.getElements().get(0).getTag() == TAG_ARG &&
                    recipe.getElements().get(1).getTag() == TAG_ARG) {

                return simpleConcat();

            } else if (mt.parameterCount() == 1 &&
                    !mt.parameterType(0).isPrimitive()) {
                // One Object argument, one constant
                MethodHandle mh = simpleConcat();

                if (recipe.getElements().get(0).getTag() == TAG_CONST &&
                        recipe.getElements().get(1).getTag() == TAG_ARG) {
                    // First recipe element is a constant
                    return MethodHandles.insertArguments(mh, 0,
                            recipe.getElements().get(0).getValue());

                } else if (recipe.getElements().get(1).getTag() == TAG_CONST &&
                        recipe.getElements().get(0).getTag() == TAG_ARG) {
                    // Second recipe element is a constant
                    return MethodHandles.insertArguments(mh, 1,
                            recipe.getElements().get(1).getValue());

                }
            }
            // else... fall-through to slow-path
        }

        // Create filters and obtain filtered parameter types. Filters would be used in the beginning
        // to convert the incoming arguments into the arguments we can process (e.g. Objects -> Strings).
        // The filtered argument type list is used all over in the combinators below.
        Class<?>[] ptypes = mt.parameterArray();
        MethodHandle[] filters = null;
        for (int i = 0; i < ptypes.length; i++) {
            MethodHandle filter = stringifierFor(ptypes[i]);
            if (filter != null) {
                if (filters == null) {
                    filters = new MethodHandle[ptypes.length];
                }
                filters[i] = filter;
                ptypes[i] = filter.type().returnType();
            }
        }

        // Start building the combinator tree. The tree "starts" with (<parameters>)String, and "finishes"
        // with the (byte[], long)String shape to invoke newString in StringConcatHelper. The combinators are
        // assembled bottom-up, which makes the code arguably hard to read.

        // Drop all remaining parameter types, leave only helper arguments:
        MethodHandle mh;

        mh = MethodHandles.dropArguments(newString(), 2, ptypes);

        long initialLengthCoder = INITIAL_CODER;

        // Mix in prependers. This happens when (byte[], long) = (storage, indexCoder) is already
        // known from the combinators below. We are assembling the string backwards, so the index coded
        // into indexCoder is the *ending* index.

        // We need one prepender per argument, but also need to fold in constants. We do so by greedily
        // create prependers that fold in surrounding constants into the argument prepender. This reduces
        // the number of unique MH combinator tree shapes we'll create in an application.
        String constant = null;
        for (RecipeElement el : recipe.getElements()) {
            // Do the prepend, and put "new" index at index 1
            switch (el.getTag()) {
                case TAG_CONST: {
                    String constantValue = el.getValue();

                    // Eagerly update the initialLengthCoder value
                    initialLengthCoder = JLA.stringConcatMix(initialLengthCoder, constantValue);

                    // Collecting into a single constant that we'll either fold
                    // into the next argument prepender, or into the newArray
                    // combinator
                    constant = constant == null ? constantValue : constant + constantValue;
                    break;
                }
                case TAG_ARG: {
                    // Add prepender, along with any prefix constant
                    int pos = el.getArgPos();
                    mh = MethodHandles.filterArgumentsWithCombiner(
                            mh, 1,
                            prepender(constant, ptypes[pos]),
                            1, 0, // indexCoder, storage
                            2 + pos  // selected argument
                    );
                    constant = null;
                    break;
                }
                default:
                    throw new StringConcatException("Unhandled tag: " + el.getTag());
            }
        }

        // Fold in byte[] instantiation at argument 0
        MethodHandle newArrayCombinator;
        if (constant != null) {
            // newArray variant that deals with prepending the trailing constant
            //
            // initialLengthCoder has been adjusted to have the correct coder
            // and length already, but to avoid binding an extra variable to
            // the method handle we now adjust the length to be correct for the
            // first prepender above, while adjusting for the missing length of
            // the constant in StringConcatHelper
            initialLengthCoder -= constant.length();
            newArrayCombinator = newArrayWithSuffix(constant);
        } else {
            newArrayCombinator = newArray();
        }
        mh = MethodHandles.foldArgumentsWithCombiner(mh, 0, newArrayCombinator,
                1 // index
        );

        // Start combining length and coder mixers.
        //
        // Length is easy: constant lengths can be computed on the spot, and all non-constant
        // shapes have been either converted to Strings, or explicit methods for getting the
        // string length out of primitives are provided.
        //
        // Coders are more interesting. Only Object, String and char arguments (and constants)
        // can have non-Latin1 encoding. It is easier to blindly convert constants to String,
        // and deduce the coder from there. Arguments would be either converted to Strings
        // during the initial filtering, or handled by specializations in MIXERS.
        //
        // The method handle shape before all mixers are combined in is:
        //   (long, <args>)String = ("indexCoder", <args>)
        //
        // We will bind the initialLengthCoder value to the last mixer (the one that will be
        // executed first), then fold that in. This leaves the shape after all mixers are
        // combined in as:
        //   (<args>)String = (<args>)

        int ac = -1;
        MethodHandle mix = null;
        for (RecipeElement el : recipe.getElements()) {
            switch (el.getTag()) {
                case TAG_CONST:
                    // Constants already handled in the code above
                    break;
                case TAG_ARG:
                    if (ac >= 0) {
                        // Compute new "index" in-place using old value plus the appropriate argument.
                        mh = MethodHandles.filterArgumentsWithCombiner(mh, 0, mix,
                                0, // old-index
                                1 + ac // selected argument
                        );
                    }

                    ac = el.getArgPos();
                    Class<?> argClass = ptypes[ac];
                    mix = mixer(argClass);

                    break;
                default:
                    throw new StringConcatException("Unhandled tag: " + el.getTag());
            }
        }

        // Insert the initialLengthCoder value into the final mixer, then
        // fold that into the base method handle
        if (ac >= 0) {
            mix = MethodHandles.insertArguments(mix, 0, initialLengthCoder);
            mh = MethodHandles.foldArgumentsWithCombiner(mh, 0, mix,
                    1 + ac // selected argument
            );
        } else {
            // No mixer (constants only concat), insert initialLengthCoder directly
            mh = MethodHandles.insertArguments(mh, 0, initialLengthCoder);
        }

        // The method handle shape here is (<args>).

        // Apply filters, converting the arguments:
        if (filters != null) {
            mh = MethodHandles.filterArguments(mh, 0, filters);
        }

        return mh;
    }

    private static MethodHandle prepender(String prefix, Class<?> cl) {
        if (prefix == null) {
            return NULL_PREPENDERS.computeIfAbsent(cl, NULL_PREPEND);
        }
        return MethodHandles.insertArguments(
                        PREPENDERS.computeIfAbsent(cl, PREPEND), 3, prefix);
    }

    private static MethodHandle mixer(Class<?> cl) {
        return MIXERS.computeIfAbsent(cl, MIX);
    }

    // These are deliberately not lambdas to optimize startup time:
    private static final Function<Class<?>, MethodHandle> PREPEND = new Function<>() {
        @Override
        public MethodHandle apply(Class<?> c) {
            return JLA.stringConcatHelper("prepend",
                    methodType(long.class, long.class, byte[].class,
                            Wrapper.asPrimitiveType(c), String.class));
        }
    };

    private static final Function<Class<?>, MethodHandle> NULL_PREPEND = new Function<>() {
        @Override
        public MethodHandle apply(Class<?> c) {
            return MethodHandles.insertArguments(
                            PREPENDERS.computeIfAbsent(c, PREPEND), 3, (String)null);
        }
    };

    private static final Function<Class<?>, MethodHandle> MIX = new Function<>() {
        @Override
        public MethodHandle apply(Class<?> c) {
            return JLA.stringConcatHelper("mix", methodType(long.class, long.class, Wrapper.asPrimitiveType(c)));
        }
    };

    private @Stable static MethodHandle SIMPLE_CONCAT;
    private static MethodHandle simpleConcat() {
        if (SIMPLE_CONCAT == null) {
            SIMPLE_CONCAT = JLA.stringConcatHelper("simpleConcat", methodType(String.class, Object.class, Object.class));
        }
        return SIMPLE_CONCAT;
    }

    private @Stable static MethodHandle NEW_STRING;
    private static MethodHandle newString() {
        MethodHandle mh = NEW_STRING;
        if (mh == null) {
            NEW_STRING = mh =
                    JLA.stringConcatHelper("newString", methodType(String.class, byte[].class, long.class));
        }
        return mh;
    }

    private @Stable static MethodHandle NEW_ARRAY_SUFFIX;
    private static MethodHandle newArrayWithSuffix(String suffix) {
        MethodHandle mh = NEW_ARRAY_SUFFIX;
        if (mh == null) {
            NEW_ARRAY_SUFFIX = mh =
                    JLA.stringConcatHelper("newArrayWithSuffix",
                            methodType(byte[].class, String.class, long.class));
        }
        return MethodHandles.insertArguments(mh, 0, suffix);
    }

    private @Stable static MethodHandle NEW_ARRAY;
    private static MethodHandle newArray() {
        MethodHandle mh = NEW_ARRAY;
        if (mh == null) {
            NEW_ARRAY = mh =
                    JLA.stringConcatHelper("newArray", methodType(byte[].class, long.class));
        }
        return mh;
    }

    /**
     * Public gateways to public "stringify" methods. These methods have the
     * form String apply(T obj), and normally delegate to {@code String.valueOf},
     * depending on argument's type.
     */
    private @Stable static MethodHandle OBJECT_STRINGIFIER;
    private static MethodHandle objectStringifier() {
        MethodHandle mh = OBJECT_STRINGIFIER;
        if (mh == null) {
            OBJECT_STRINGIFIER = mh =
                    JLA.stringConcatHelper("stringOf", methodType(String.class, Object.class));
        }
        return mh;
    }
    private @Stable static MethodHandle FLOAT_STRINGIFIER;
    private static MethodHandle floatStringifier() {
        MethodHandle mh = FLOAT_STRINGIFIER;
        if (mh == null) {
            FLOAT_STRINGIFIER = mh =
                    lookupStatic(MethodHandles.publicLookup(), String.class, "valueOf", String.class, float.class);
        }
        return mh;
    }
    private @Stable static MethodHandle DOUBLE_STRINGIFIER;
    private static MethodHandle doubleStringifier() {
        MethodHandle mh = DOUBLE_STRINGIFIER;
        if (mh == null) {
            DOUBLE_STRINGIFIER = mh =
                    lookupStatic(MethodHandles.publicLookup(), String.class, "valueOf", String.class, double.class);
        }
        return mh;
    }

    private static final ConcurrentMap<Class<?>, MethodHandle> PREPENDERS;
    private static final ConcurrentMap<Class<?>, MethodHandle> NULL_PREPENDERS;
    private static final ConcurrentMap<Class<?>, MethodHandle> MIXERS;
    private static final long INITIAL_CODER;

    static {
        INITIAL_CODER = JLA.stringConcatInitialCoder();
        PREPENDERS = new ConcurrentHashMap<>();
        NULL_PREPENDERS = new ConcurrentHashMap<>();
        MIXERS = new ConcurrentHashMap<>();
    }

    /**
     * Returns a stringifier for references and floats/doubles only.
     * Always returns null for other primitives.
     *
     * @param t class to stringify
     * @return stringifier; null, if not available
     */
    private static MethodHandle stringifierFor(Class<?> t) {
        if (!t.isPrimitive()) {
            return objectStringifier();
        } else if (t == float.class) {
            return floatStringifier();
        } else if (t == double.class) {
            return doubleStringifier();
        }
        return null;
    }

    private static MethodHandle lookupStatic(Lookup lookup, Class<?> refc, String name,
                                     Class<?> rtype, Class<?>... ptypes) {
        try {
            return lookup.findStatic(refc, name, MethodType.methodType(rtype, ptypes));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private StringConcatFactory() {
        // no instantiation
    }
}
