(ns infer.optimize
  {:doc "Standard functions for numerical optimziation. Only very marginally tuned
   for performance. Lots of optional configurability. Defaults are optimized for
   either gradient descent or quasi-Newton methods, which is what you'll end up doing
   for many batch trained ML algorithms. 
         
   NOTE: This ns assumes you're trying to always minimize, rather than maximize a function. 
   
   Different from infer.learning, which mixes optimization with actual objectives, which is approriate
   for the kind of problems there. This ns is for arbitrary function optimization. 
   You give me a function with a value and gradient, I'll optimize it!"   
   :author "Aria Haghighi <me@aria42.com>"}
  (:use [clojure.contrib.def :only [defvar]]
        [infer.measures :only [within dot-product]]))
    
;; Global Variables/Options  
(defvar *step-size-underflow* 1.0e-20 "When to bail on line search")
(defvar *eps* 1.0e-4 "Tolerance parameter for convergence")

(defn- make-affine-fn 
  "for number seqs x,y, return f: alpha -> x + alpha* y"
  [x y]
  (fn [alpha] (map #(+ %1 (* alpha %2)) x y)))

(defn- make-step-fn 
  "for a function f, returns the function 
   step-size -> f(x0 + step-size * step-dir)
   
   This is the function which line search would optimzie. 
   
   f: arbitrary function takes seq of numbers as input
   x0: initial point for f
   step-dir: direction for f"  
  [f x0 step-dir]
  (comp f (make-affine-fn x0 step-dir)))                          

(defn backtracking-line-search 
  "search for step-size in step-dir to satisfy Wolfe Conditions:
     f(x0 + step-size*step-dir) <= f(x0) + suff-descrease * step-size * grad-f(x_0)' delta
   returns satisfying step-size
   
   f: x -> [f(x) grad-f(x)]
   x0: initial point (use this to get dimension of f). should be seqable
   step-dir: vector of same dimensions as x0. should be seqable
   
   Options are...
   suff-descrease: Wolfe condition constant (default to 1.0e-4)
   step-size-multiplier: How much to decrease step-size by each iter (default to 0.1)
      
   TODO: Add curvature lower-bound"   
  [f x0 step-dir &
    {:as opts
     :keys [suff-decrease, step-size-multiplier] 
     :or {suff-decrease 0.001 step-size-multiplier 0.1}}]
    (let [[val grad]  (f x0)
          step-fn (make-step-fn f x0 step-dir)
          directional-deriv (dot-product step-dir grad)
          target-val (fn [step-size] (+ val (* suff-decrease step-size directional-deriv)))]
      (loop [step-size 1.0]        
        (cond
          (< step-size *step-size-underflow*)
            (RuntimeException. "Line search: Stepsize underflow. Probably a gradient computation error")
          (<= (-> step-size step-fn first) (target-val step-size))  
            step-size            
          :default (recur (* step-size-multiplier step-size))))))
          
(defprotocol QuasiNewtonApproximaiton
  (inv-hessian-times [this z] "Implicitly multiply H^-1 z for quasi-newton search direction")
  (update-approx [this x-new x-old grad-new grad-old] 
    "Update approximation after new x and grad points. Should return a new QuasiNewtonApproximaiton"))
  
(defrecord LBFGSApprixmation [max-history-size x-deltas grad-deltas]

  QuasiNewtonApproximaiton
  ; See http://en.wikipedia.org/wiki/L-BFGS  
  (inv-hessian-times [this z]
    (let [alphas (map (fn [x-delta grad-delta] 
                        (let [curvature (dot-product x-delta grad-delta)]
                          (when (< curvature 0)
                            (throw (RuntimeException. 
                              (str "Non-positive curvature: " x-delta " " grad-delta))))
                          (* (/ (dot-product x-delta z) curvature))))
                       x-deltas grad-deltas)
          left (reduce (fn [res [alpha grad-delta]]
                          (map #(- %1 (* alpha %2)) res grad-delta))
                       z (map vector alphas grad-deltas))]
      (reduce (fn [res [alpha x-delta grad-delta]]
                (let [rho (/ 1.0 (dot-product x-delta grad-delta))
                      b (* rho (dot-product grad-delta res))]
                  ((make-affine-fn res x-delta) (- alpha b))))
              left
              (map vector alphas x-deltas grad-deltas))))
                        
  (update-approx [this x-new x-old grad-new grad-old]
    (let [x-delta (map - x-new x-old)
          grad-delta (map - grad-new grad-old)]
      (LBFGSApprixmation.
        max-history-size
        (take max-history-size (conj x-deltas x-delta))
        (take max-history-size (conj grad-deltas grad-delta))))))
  
(defn- new-lbfgs-approx [max-history-size]
  (LBFGSApprixmation. max-history-size '() '()))  
            
         
(defn- quasi-newton-iter
  "A single iteration of quasi-newton optimization. Takes same options as quasi-newton-optimize.
   Returns new estimate of arg min f
  
  f: x => [f(x) grad-f(x)]
  x0: current estimate
  qn-approx: Implements QuasiNewtonApproximaiton
  for efficiency, you want to implicitly multiply inv-hessian approximation 
  with target vector using quasi-newton approximation. "
  [f x0 qn-approx opts]
  (let [grad  (second (f x0))        
        step-dir (map - (inv-hessian-times qn-approx grad))
        step-fn (make-step-fn f x0 step-dir)
        step-size (apply backtracking-line-search f x0 step-dir (flatten opts))]
    ((make-affine-fn x0 step-dir) step-size)))
    
    
(comment
  (def f (fn [[x]] [(- (* x x) 1) [(* 2 x)]]))
  (quasi-newton-iter f [10] (new-lbfgs-approx 1) {})
  (quasi-newton-optimize f [10] (new-lbfgs-approx 1))
  flatten)    
                      
(defn quasi-newton-optimize 
  "Does quasi-newton optimization. Pass in QuasiNewtonApproximation
   to do either exact newton optimization or LBFGS. Parameters:
   
   qn-approx: Implements QuasiNewtonApproximation (should use (new-lbfgs-approx))
   f: function to optimize
   x0: initial point
      
   Has several options you don't need to worry about
   max-iters: How many iterations before we bail (default 50)
   step-size-multiplier: Step-size-multiplier for inner line search
   init-step-size-multiplier: Step-size multiplier for first iter (default 0.5). 
   For many convex functions, the first line search should be more careful to get
   scale of problems. Definitely true for conditional likelihood objectives. "
  [f x0 qn-approx &
   {:as opts
    :keys [history-size, max-iters, init-step-size-multiplier, step-size-multiplier]
    :or {history-size 9, max-iters 50, init-step-size-multiplier 0.5, step-size-multiplier 0.1}}]
  (loop [iter 0 x x0 qn-approx qn-approx]
    (let [[val grad] (f x)          
          new-x (quasi-newton-iter f x qn-approx
                  (if (zero? iter)
                    (assoc opts :step-size-multiplier init-step-size-multiplier) 
                    opts))
          [new-val new-grad] (f new-x)
          converged? (within *eps* val new-val)]
      (if (or converged? (= iter max-iters))
        x        
        (recur (inc iter) new-x (update-approx qn-approx new-x x new-grad grad))))))
        
        
(defn lbfgs-optimize
  "Run LBFGS Optimization. Convenience wrapper for quasi-newton-optimize with LBFGS as the QuasiNewton
   approximation. You can pass in any options that quasi-newton-optimize uses, but also there is a 
   :max-history-size (default 9) option specific to LBFGS"
  [f x0 &
    {:as opts
     :keys [max-history-size]  
     :or {max-history-size 9}}]
  (apply quasi-newton-optimize f x0 (new-lbfgs-approx max-history-size) (flatten opts)))        