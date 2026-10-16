# SeDi project

SeDi == Semantic Diff

## Purpose of the project

When coding in Java with AI I'm always confronted with diffs to review the code
written by the LLM (in fact it is the same with code written by humans)
The problem is that the diffs are usually presented as standard colored
unified diffs. This is far from ideal presentation of diffs because all
the changes have the same level of importance and are mixed together.


Some examples of it would be:

### White space

When reading code one is absolutely not interested in added/removed
spaces, tabs or newlines.

### Imports 

When someone starts using a new class it is usually reflected in the import
section. But that's usually visible in the code. So showing the changes in
the imports is just a clutter. Like the diff of imports in following diff 
is not very important to show

```patch
-import java.util.LinkedList;
+import java.util.ArrayList;
 import java.util.List;
 
 public class ListImpl {
 
-    List<String> l = new LinkedList<>();
+    List<String> l = new ArrayList<>();
 
 }

```

### Renames 

If someone just renames a local variable, field or a method then all the
changes which are the rename or handling the rename is from the semantic 
view just empty diff. Here is an example of effectively empty diff :

```diff
 public class Rename {
 
-    static int j = 1;
+    static int count = 1;
 
-    static void m1() {
+    static void method() {
 
-        int l = 0;
+        int sum = 0;
 
-        for( int k = 0; k < j; k++) {
-            l += k;
+        for(int i = 0; i < count; i++) {
+            sum += i;
         }
 
+
     }
 
     public static void main(String ...args) {
-        m1();
+        method();
     }
 
 }
```

### Order changes

If just the order of members in a class changes the diff is also
effectively empty. Here is an example.

```diff
 public class Order {
 
-    int a = 1;
     float p = 3.14;
+    int a = 1;
+
+    int m2() {
+        return (int)p;
+    }
 
     void m1() {
         System.out.println( a * p );
     }
 
-    int m2() {
-       return (int)p;
-    }
+
 
 }

```

## Solution

The final product should not hide anything. It should create categories of changes
based on theirs importance. And show the most important first and let the reviewer 
dig deeper if necessary.

The order of the categories (i.e. the importance of the categories) is a thing
to be fine-tuned later. But it could be something like

1. Java docs
2. Making things more visible or less constant
   - Extending class member visibility changes. E.g. moving from private to protected and
   - Making final members not final
   - Making static members instance
   - Changing permits clause of classes
3. Other API incompatible changes
   - Changing types of members, parameters
   - Added/removed types
   - Adding exceptions to methods
4. Implementation changes. E.g. changes of implementation of a method or field initializer
5. Local renames
6. Necessary refactoring changes e.g. imports or calls to renamed method
7. Cosmetic code changes - pure rename of field, method parameter or local variable
8. White space

## Possible implementation

When we have a changed file we could use two sources of information:

1. The git status and git diff. First will tell us added/removed types the former
   when compared with the parsed tree could help answering the question of changed vs.
   added/removed fields, methods etc.
2. Parsed and attributed three created by calling javac on the changed files. We coudld traverse
   the AST and compute SHAs for the tree for the categories. E.g. ignoring names, ignoring types etc.
   Each category will have SHA for given member for the current and previous revision. E.g.
   if the SHA computed for a method by ignoring the names of params and local variables will tell us
   if some real change in the implementation happened.

## Diff presentation

### Use strikethrough instead of two line for small diffs

When changing a type and name of a parameter  of a method I would rather than

```diff
-    public String method( int i ) {
+    public String method( float f ) {
```
I would like to see

   public String method( ~~int~~ float ~~i~~ f )    

### Badge backwards incompatible API changes

It would be very nice if a change in a now public class or interface is backward
incompatible from the API perspective would be marked as such with a badge. E.g

* Adding method to an interface which does not restrict the implementors 
* Adding a method to non-final class
* Removing a method from a class
* Widening return type or narrowing parameter type 
* ...

### Rating changes

Changes which make API more strict will be rated positive and changes
which make the API less strict or are incompatible would get negative rating.
We could potetially compute rate for the whole diff

## Implementation plan

Everything should be implemented in java and the product should be one 
.jar containing all dependencies.

### Phase 0 : necessary infrastructure

We need some basic infrastructure. I.e. something what is
capable of :
- querying git (using jgit) decide if there are changes in the working copy. 
   If so use them. If not use latest commit.
- Do a structured diff of the files. Usinf jGit's DiffFormatter
- Load a attributed AST of all the changed files including the positions
  and the comments 

We also need some basic tests support e.g. something what is capable of:
- create a git repo in a temporary folder
- create initial commit to the repo
- do predefined changes of the files in the working copy (probably specified
  as new content of the file or a patch)

We need to add tests for the infrastructure and the test support itself
  
### Phase 1 : basic functionality and tests

Create first implemtation of inspecting the diffs and looking deciding about
which category given change fits in

Create unit tests using the test infrastructure from the phase 0 and create 
sample data for the change categories. Test if the changes are categorized 
correctly.

### Phase 2 : maven

* After starting the app we should look for pom.xml and try to guess the 
  classpath using the maven API. In order not to have to specify it in
  using CLI options.

### Phase 3 : watch the folder and use jgit

* After the app is tarted it should watch current folder recursively and
  after each change show the change categories of the changed file in console
  ideally colored properly. 
* Behavior of the app should be configurable using command line parameters 
  we should use picocli for parsing them. This can include specifying the
  class path of the project

After finishing this change we can start using the project for the project
itself.

### Phase 4 : add web server

* After starting the app should start internal web server and open the page in
  browser. After changes of the a file the page should be reloaded and show the 
  changes which were before visible in console. Just with nicer UI. 
* Maybe the categories of changes should be interactively switched for individual
  classes. This is TBD and will require "testing on humans"

### Phase 5: more advanced features POC

Pick some types of refactorings e.g. rename and try to recgonize them. 
E.g. when renaming the method the rename itself will be in the API category
but all calls to the method would be in "Necessary Refactoring Changes."

### Phase 6 : additional features POC

Try flagging backward incomatibilies and rating of the API changes. 

## Out of scope

This project is purely experimental POC. We do not need take into account
all complexities of real wold projects. I.e.:

* No other languages than Java
* Only simple maven project - no fancy maven configurations
* For now no support for Java modules

## Coding rules

- Make sure all public classes and its members always have actual javadoc
- Do small commits with reasonable commit messages
- Do NOT add ANSI colors to the commit messages
- If possible write use one agent to write implementation and other agent 
  to write the tests.
- Will only be run on Java 25+ so it is fine to use latest Java features.
- Try to make the public interfaces of the types as minimal as possible
- Try to keep things simple
- Do not add any dependencies to the project without explicite approval.

  
