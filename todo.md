## API

* [ ] Some smart algorithm for detecting renames om innertypes
* [x] Removed inner types are not in type and are not printed striked through and do not have signature  
* [x] Ignore type renames
* [x] Incorrect printing of diffs in API changes
* [x] Javadoc - word diff

## IMPL

* [ ] Refactoring detection (At least extract method)
* [ ] Ignore all renames, but not signature changes
* [ ] Expansion/collapse of context using -> <-
* [x] Generic rendering of diffs usng text or tokens
* [x] Cosmetic change qualified name to simple name
* [x] When something is added e.g. parameter it should be underscored (maybe works)
* [x] ~ List<String> new LinkedList ArrayList<>() should be ~ List<String> l = new LinkedList ArrayList<>()

## IMPORTS

* [x] Strike through deleted
* [ ] Some smart algorithm for diffing imports (equal package + edit distance on simplename (or camel case tokenization + edit distance) )

## OTHER

* [ ] Fix local and anonymous classes 
* [x] Add line numbers (also means fixing new lines)
* [x] Create demo repo - in orphan branch
* [ ] Other files 
* [ ] Webapp?
* [x] If section is empty say so.
* 

## QUESTIONS

* [ ] What about methods which implement a method in interface should the renames be hidden too e.g. Greetr.great -> Greeter.greet
