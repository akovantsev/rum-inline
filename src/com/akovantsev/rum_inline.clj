(ns com.akovantsev.rum-inline
  ;(:require
  ; [clojure.walk :as walk]
  ; [rum.core :as rum])
  (:import
   [java.math BigInteger]
   [java.security MessageDigest]))


;; https://gist.github.com/jizhang/4325757?permalink_comment_id=1993162#gistcomment-1993162
(defn -md5 [^String s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm (.getBytes s))]
    (format "%032x" (BigInteger. 1 raw))))


(defmacro inline
  "
  (rum/defc Parent [a b]
    (let [c 1, d 2]
      [:table
       [:tbody
        (for [idx (range 10)]
          (inline ^{:key idx :name \"MyInlineRowComp\"}
            [:tr {}
             [:td (+ idx c d)]
             (inline [!x (+ 1 idx)]
               [:td (str a \"-\" @!x)])]))]]))

  roughly expands into:

  (rum/defc Parent [a b]
    (let [c 1, d 2]
      [:table
       [:tbody
        (for [idx (range 10)]
          (->
            (do
              (when-not (exists? MyInlineRowComp_form_md5)
                (rum.core/defcs MyInlineRowComp_form_md5 < rum.core/static
                  [rum-state idx c d a]
                  [:tr {}
                   [:td (+ idx c d)]

                   (do
                     (when-not (exists? InlineComponent_form_md5)
                       (rum.core/defcs InlineComponent_form_md5 < rum.core/static
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
                            [:td (str a \"-\" @!x)])))

                     (InlineComponent_form_md5 a idx))]))

              (MyInlineRowComp_form_md5 idx c d a))
            (rum.core/with-key idx)))]]))

  Differences from actual macroexpansion:

  `exists?` is `lifted` clj/s defonce macro, and looks a bit uglier.

  clojure.walk/macroexpand-all does not reflect reality, because it expands
  without passing/setting the &env, and I use &env to figure out arguments for components.

  Also, all* symbols in expanded form are fully qualified or generated#, e.g.:
  rum-state-d9d4fcd335a2b04cdaade55c80cf669b
  "
  ([body] (with-meta `(inline [] ~body) (meta &form)))
  ([bindings body]
   (assert (-> bindings count even?) bindings)
   (let [get-meta#  (fn [kw]
                      (or
                        (-> &form meta kw)
                        (-> bindings meta kw)
                        (-> body meta kw)))
         react-key# (get-meta# :key)
         name#      (get-meta# :name)
         ;; not a gensym symbols because on live reload, if parent component's code did not change,
         ;; macroexpand expands 'inline' with new gensym ints and you get:
         ;  > Use of undeclared Var links.ops/InlineComponent_132456
         ;; and since there is no access to parent's anything from inside here -
         ;; I use (the only?) stable thing - md5 of form itself:
         suff#      (-> body pr-str -md5)
         comp#      (symbol (str (or name# "InlineComponent") "_" suff#))
         state#     (symbol (str "rum-state-" suff#))
         rerender#  (symbol (str "rerender-" suff#))

         cljs?#     (:ns &env)
         exists?#   (if cljs?# ;; from cljs/clojure defonce:
                      `(cljs.core/exists? ~comp#)
                      `(.hasRoot (def ~comp#)))
         locals#    (if cljs?#
                      (-> &env :locals keys set)
                      (-> &env keys set))
         args#      (->> body (tree-seq coll? identity) (filter locals#) distinct vec)
         atom-syms# (->> bindings (partition 2) (mapv first))
         reactive?# (->> body (tree-seq coll? identity) (some #{'rum.core/react 'rum/react}))

         ;; this is 'init local atom from args'
         atoms-mx#  `{:will-mount
                      (fn [~state#]
                        (let [[~@args#] (:rum/args ~state#)
                              ~@(->> bindings
                                  (partition 2)
                                  (mapcat (fn [[k# expr#]] [k# (list 'clojure.core/atom expr#)])))
                              ~rerender# (fn [_# _# o# n#]
                                           (when (not= o# n#)
                                             (-> ~state# :rum/react-component ~'rum.core/request-render)))]
                          ~@(->> atom-syms#
                              (map (fn [k#] (list 'clojure.core/add-watch k# ::request-rerender rerender#))))
                          (assoc ~state# ::atoms ~atom-syms#)))}

         defc#      `(~'rum.core/defcs ~comp#
                      ~'<
                      ~'rum.core/static
                      ~@(when reactive?# ['rum.core/reactive])
                      ~@(when (seq bindings) [atoms-mx#])
                      [~state# ~@args#]
                      (let [~atom-syms# (::atoms ~state#)]
                        ~body))
         return#    (if-not react-key#
                      `(-> (do (if (not ~exists?#) ~defc#) (~comp# ~@args#)))
                      `(-> (do (if (not ~exists?#) ~defc#) (~comp# ~@args#))
                         (~'rum.core/with-key ~react-key#)))]

     (clojure.pprint/pprint return#)
     return#)))