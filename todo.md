## API

* [ ] Ignore type renames
* [ ] Incorrect printing of diffs in API changes
* [ ] Javadoc - word diff

## IMPL

* [ ] ~ List<String> l
  new LinkedList ArrayList<>() should be ~ List<String> l = new LinkedList ArrayList<>()
* [ ] Ignore all renames, but not signature changes
* [ ] When something is added e.g. parameter it should be underscored (maybe works)
* [ ] Expansion/collapse of context using -> <-

## IMPORTS

* [x] Strike through deleted
* [ ] Some smart algorithm for diffing imports (equal package + edit distance on simplename (or camel case tokenization + edit distance) )

## OTHER

* [ ] Fix local and anonymous classes 
* [ ] Add line numbers (also means fixing new lines)
* [ ] Create demo repo.
* [ ] Other files 
* [x] If section is empty say so.
* 
* [ ]
