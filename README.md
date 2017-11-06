![](https://github.com/blak3mill3r/keenest-rube/blob/master/images/1.png)

## `[keenest-rube "0.1.0-alpha0"]`

[![Clojars Project](https://img.shields.io/clojars/v/keenest-rube.svg)](https://clojars.org/keenest-rube)

## A Clojure tool for Kubernetes operations

> * Cluster state is a value in an atom, the API calls are abstracted away

> * Manage your resources from the comfort of your Clojure REPL

> * Or, use it to build a Kubernetes abstraction...

#### Demo?

> ###### This one time, when I had completed [the setup](#setup) already...

```clojure
;; I started poking around...

(count @pods)                   ;; => 0
(count @replicationcontrollers) ;; => 0
(count @services)               ;; => 0

```


###### &nbsp; *;;* &nbsp;&nbsp;&nbsp;&nbsp; :-1:
 

```clojure
;; and then I was all, like
(swap! pods assoc :my-awesome-podz
       {:metadata
        {:labels {:app "bunk" :special "false"} :namespace "playground" :name "my-awesome-podz"}
        :spec
        {:containers
         [{:name
           "two-peas"
           :env
           [{:name "REDIS_URL" :value "prolly.xwykgm.ng.0001.use1.cache.amazonaws.com:6379"}]
           :ports
           [{:containerPort 420, :protocol "TCP"}]
           :image
           "1337.dkr.ecr.us-east-1.amazonaws.com/two-peas:v1.0"}]
         :restartPolicy "Always",
         :terminationGracePeriodSeconds 30}})
```

###### &nbsp; *;;* &nbsp;&nbsp;&nbsp;&nbsp; `=>` :fire:

```clojure
;;        (or if we're being optimistic, a new value for the pods atom)

;; and after a moment, a running pod:
(-> @pods
    :my-awesome-podz
    :status
    :containerStatuses
    first
    :ready) ;; => true

;;    and similarly for other resources...
;;       the Clojure data matches the JSON of the k8s API
;;          which is good, because there are no docs (yet)
```

#### Design
> * Kubernetes' [API](https://kubernetes.io/docs/api-reference/v1.8/) is an uncommonly good one, it's very consistent
>   * It bends the REST rules and supports streaming updates to a client
>   * given a `WATCH` request, it will keep the response body open, and continue to send lines of JSON

> * On initialization, `keenest-rube` `GET`s a (versioned) list of each kind of resource,
>   initializes the atom, and then starts tailing a `WATCH` request starting from that version
>   * this ensures that the atom is kept closely in sync with Kubernetes (few hundred ms, tops)
>   * this uses [`aleph`](https://github.com/ztellman/aleph) behind the scenes

> * Reading the state of the cluster is as easy as dereferencing it...

> * The [abstraction over mutations](src/rube/lens.clj) is provided by a ["derived atom" from `lentes`](http://funcool.github.io/lentes/latest/#working-with-atoms) for each kind of resource
>   * the `pods` atom in the demo, for example
>   * the value in it is a map (by name) of all the pods in the namespace
>   * these atoms make an API call as a side-effect of an update (by diffing)
>     * You `swap!` the atom, `keenest-rube` does `PUT`, `DELETE`, and `POST`
>   * All API errors throw, and the state of the atom is updated *only* using data from Kubernetes.
>     * *Your own updates to it are more like suggestions...*
>     * Only API responses from mutations, and the data from a `WATCH` stream, update the atom
>   * Multiple resource mutations in a single `swap!` are [explicitly disallowed](src/rube/lens.clj#L83)
>     * Because you'll be wanting to see the error message if a mutation fails

### Setup

> :point_right: &nbsp;&nbsp;&nbsp;&nbsp; If you just want to try it out,

> &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;   just clone this repo, launch a repl, and look at [`dev/user.clj`](dev/user.clj)

If you want to use it in your own project, you'll want something to manage the state...

Supposing we want to use [`mount`](https://github.com/tolitius/mount) and [`leiningen`](https://leiningen.org/):

> In `project.clj`

```clojure
:dependencies [[keenest-rube "0.1.0-alpha0"]] ;; the "alpha" is for realz
:profiles {:dev {:dependencies [[mount "0.1.11"]]}}
```

> and in `src/your/playground.clj`

```clojure
(ns your.playground
  (:require
   [mount.core :as mount :refer [defstate]]
   [rube.core :as k]))

(defstate kube
  :start (k/intern-resources ;; this interns a var for each k8s resource
          (k/cluster
           {:server "http://localhost:8080" ;; kubectl proxy (or whatever)
            :namespace "playground"}))      ;; not production! (yet)
  :stop (k/disconnect! kube))
```

### Disclaimer

> This is `alpha` for a reason. It has not been thoroughly tested, it may misbehave, and the API may change.

> Use it at your own risk.

> That being said, it is restricted to operating within a k8s *namespace*
 
> so it should be mostly harmless...

### Is it any good?

> Yes.

### Contributing

> Fork it, send me a pull request!

### License

> [MIT](LICENSE)

### Similar work

https://github.com/nubank/clj-kubernetes-api

This library is quite different:

It's solid, much more low-level, providing Clojure helpers for each API call, generated from the official Swagger specs. It's also more complete than `keenest-rube`, which is young and has limitations. There's no reason you can't use them side by side, though.

Also I found it to be a valuable resource, and borrowed a couple of helper functions, so with many thanks to the author(s) of that library, I'll call this a derivative work.