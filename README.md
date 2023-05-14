## What

`inline` macro to mark hiccup subtree as `rum` component inline.

## Why

There are 3 reasons why you'd want to extract piece of hiccup as component:
- improve readability
- implement once, call in N places
- improve re-rendering speed by introducing component boundary.

`inline` enables you to declare "component boundary" without:
- coming up with a new component name,
- breaking code locality, while reading/writing.
- figuring out arguments and component signature,
- and then failing to pass arguments in the right order on the 1st try,
- a need to manage component's signature and call-site when (sub)component's body changed slightly.
 

## Install

```clojure
;; in deps.edn
{:deps {github-akovantsev/rum-inline
        {:git/url "https://github.com/akovantsev/rum-inline"
         :sha     "..."}}} ;; actual sha
```

## Features

- Supports live reload
- Can nest (almost) any number of inline components.
  <br>One limitation would be java's "Method code too large":
  <br>code_length item must be less than 65536.
  <br>https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.9.1
- Accepts react key `:key`, and component name `:name` as metadata to either form, bindings, or body (both are optional):
   ```clojure
   ^{:key some-id}
   (inline [my-local-atom (init expr)]
     [:tr @my-local-atom])
  
   (inline ^{:key some-id :name "ILikeItHere"}
     [my-local-atom (init expr)]
     [:tr @my-local-atom])
  
   (inline
     [my-local-atom (init expr)]
     ^{:key some-id}
     [:tr @my-local-atom])
   
   (inline
     [:tr "no local atoms here"])
   ```
- has 2 arities `[body]` and `[local-atoms-bindings body]`,
  <br>where `local-atoms-bindings` is vec of pairs `[my-local-atom-1 (init expr), my-local-atom-2 (init expr 2)]`
  <br>a more convenient analog of `rum/local`, but `init expr` can use any symbol defined above, and
  <br>`inline` will pass those symbols as component args, and init atom in `:will-mount` mixin.


## TODO
- Accept mixins in metadata. Might be useful for inline form inputs.
- Maybe figureout how to reduce component code size, if I ever hit size limit in reasonable case.
- Now, if you shadow any parent's local binding, original will still be passed as arg. 
  <br>This might affect re-render speed (will doing extra `=` on args),
  <br>or cause unnecessary re-renders (when arg has changed but component does nto use it).
  <br>But current implementation is so light, so I don't want to overcomplicate it just for this fix (yet).

## Example

(From docstring)
```clojure
(rum/defc Parent [a b]
  (let [c 1, d 2]
    [:table
     [:tbody
      (for [idx (range 10)]
        (inline ^{:key idx :name "MyInlineRowComp"}   ;; notice ^.
          [:tr {}
           [:td (+ idx c d)]
           (inline [!x (+ 1 idx)]       ;; this is local atom sym and init expr.
             [:td (str a "-" @!x)])]))]]))
```
(`!x` is not special, it is just atom name. Maybe, you are more used to `*x` instead).

<br>Roughly expands into:

```clojure
(rum/defc Parent [a b]
  (let [c 1, d 2]
    [:table
     [:tbody
      (for [idx (range 10)]
        (->
          (do
            (when-not (exists? MyInlineRowComp_form_md5)   ;; effectively defonce.
              (rum.core/defcs MyInlineRowComp_form_md5 < rum.core/static
                [rum-state idx c d a]
                
                [:tr {}
                 [:td (+ idx c d)]

                 (do
                   (when-not (exists? InlineComponent_form_md5)
                     (rum.core/defcs InlineComponent_form_md5 < rum.core/static
                       ;; equivalent of rum/local, but accepts symbols from args.
                       ;; (Imagine manually keeping args order and mixins in sync. oh horror!)
                       {:will-mount
                        (fn [rum-state]
                           (let [[a idx]  (:rum/args rum-state)
                                 !x       (atom (+ 1 idx))
                                 rerender (fn [_ _ o n]
                                            (when (not= o n)
                                              (-> rum-state :rum/react-component rum.core/request-render)))]
                             (add-watch !x ::request-rerender rerender)
                             (assoc rum-state ::atoms [!x])))}
                       
                       [rum-state a idx]
                       
                       (let [[!x] (::atoms rum-state)]
                         [:td (str a "-" @!x)])))

                   (InlineComponent_form_md5 a idx))]))

            (MyInlineRowComp_form_md5 idx c d a))
          (rum.core/with-key idx)))]]))
```

Differences from the **actual** macroexpansion:

`exists?` is `lifted` clj/s `defonce` macro, and looks a bit uglier (see source).

`clojure.walk/macroexpand-all` does not reflect reality, because it expands
without passing/setting the `&env`, and I use `&env` to figure out arguments for components.

Also, all* symbols in expanded form are fully qualified or generated#, e.g.:
`rum-state-d9d4fcd335a2b04cdaade55c80cf669b`