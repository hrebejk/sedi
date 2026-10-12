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
5. Plain renames
6. Cosmetic code changes - pure rename of field, method parameter or local variable
7. White space

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

## Implementation plan

Everything should be implemented in java and the product should be one 
.jar containing all dependencies.

### Phase 1 : basic tests

We will create unit tests for the individual use cases of the product. E.g.
The tests should:
- create temp folder
- initialize a git repo in the folder
- create commit with the initial version e.g. add a .java file
- do changes to the file. e.g. rename a method
- compute the change categories using jgit and javac 
- make sure the categories of changes do contain what they are supposed to contain.

### Phase 2 : watch the folder and use jgit

* After the app is tarted it should watch current folder recursively and
  after each change show the change categories of the changed file in console
  ideally colored properly. 
* Behavior of the app should be configurable using command line parameters 
  we should use picocli for parsing them. This can include specifying the
  class path of the project

### Step 3 : maven

* After starting the app we should look for pom.xml and try to guess the 
  classpath using the maven API. In order not to have to specify it in
  using CLI options.

### Step 4 : add web server

* After starting the app should start internal web server and open the page in
  browser. After changes of the a file the page should be reloaded and show the 
  changes which were before visible in console. Just with nicer UI. 
* Maybe the categories of changes should be interactively switched for individual
  classes. This is TBD and will require "testing on humans"

## Out of scope

* No other languages than Java
* Only simple maven project - no fancy maven configurations

## Coding rules

- Make sure all public classes and its members always have actual javadoc
- Do small commits with reasonable commit messages
- Do NOT add ANSI colors to the commit messages
- If possible write use one agent to write implementation and other agent 
  to write the tests.