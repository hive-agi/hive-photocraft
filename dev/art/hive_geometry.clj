(ns hive-geometry
  "Live PhotoCraft composition. Evaluate from the art worktree nREPL; never log the token."
  (:require [hive-photocraft.transport.socket :as socket]
            [hive-photocraft.transport :as transport]))

(def transport (socket/socket-transport
                {:port 38558 :token-ref {:scheme :file
                                         :path "/home/klein/.cache/hive-craft-art/control.token"}}))
(def measurements (atom {:calls 0 :ms 0 :errors []}))
(defn call! [method params]
  (let [t (System/nanoTime)
        response (transport/send-request transport
                    {"id" (str (java.util.UUID/randomUUID)) "method" method "params" params})
        elapsed (long (/ (- (System/nanoTime) t) 1000000))
        reply (:ok response)
        error (or (:error response) (when (false? (get reply "ok")) (get reply "error")))]
    (swap! measurements (fn [m] (cond-> (-> m (update :calls inc) (update :ms + elapsed))
                                  error (update :errors conj {:method method :error error}))))
    (when error (throw (ex-info "PhotoCraft control call failed" {:method method :error error})))
    (get reply "result")))
(defn exec! [id params] (call! "engine.execute" {"command" id "params" params}))
(defn screenshot! [name] (call! "ui.screenshot" {"path" name}))
(def tau (* 2.0 Math/PI))
(defn polar [cx cy r a] [(+ cx (* r (Math/cos a))) (+ cy (* r (Math/sin a)))])
(defn hex [x y radius]
  (mapv #(polar x y radius (+ (/ Math/PI 6) (* % (/ tau 6)))) (range 6)))
(defn path-of [polys]
  {"subpaths" (mapv (fn [points] {"closed" true "knots" points}) polys) "fillRule" "nonzero"})
(defn shape! [name polys fill stroke]
  (exec! "shape.create" {"kind" "path" "path" (path-of polys) "name" name
                          "fill" fill "stroke" stroke}))
(defn line! [name points color width]
  (exec! "shape.create" {"kind" "path" "path" {"subpaths" [{"closed" false "knots" points}]}
                          "name" name "fill" nil "stroke" {"color" color "width" width}}))
(defn circle! [name x y radius fill stroke]
  (exec! "shape.create" {"kind" "ellipse" "name" name
                          "rect" [(- x radius) (- y radius) (* 2 radius) (* 2 radius)]
                          "fill" fill "stroke" stroke}))
(defn honey-cells []
  (for [row (range -1 13) col (range -1 20)
        :let [x (+ 80 (* 85 col) (* 42.5 (mod row 2)))
              y (+ 42 (* 73.6 row))]
        :when (and (< -60 x 1660) (< -60 y 1060))]
    [x y]))
(defn draw! []
  (assert (= "/home/klein/PP/hive/hive-photocraft-wt/art" (System/getProperty "user.dir")))
  ;; A dark midnight canvas carrying a deep-honey radial atmospheric gradient.
  (exec! "gradient.fill.create" {"from" [820 510] "to" [1560 1020] "style" "radial"
                                  "stops" [[0 "#714414"] [0.3 "#3e3224"] [0.72 "#192b37"] [1 "#101927"]]})
  (screenshot! "stage-01-atmosphere.png")
  ;; Single compound vector shape keeps 230 precision-drafted comb cells editable.
  (shape! "01 / infinite honeycomb · gold hairlines"
          (mapv (fn [[x y]] (hex x y 47)) (honey-cells)) nil
          {"color" "#c5974b" "width" 1.5 "opacity" 42})
  (screenshot! "stage-02-lattice.png")
  ;; Phyllotaxis: golden-angle lights shrink slowly away from the central eye.
  (doseq [i (range 64)]
    (let [angle (* i 2.399963229728653)
          r (* 34 (Math/sqrt (double i)))
          [x y] (polar 800 500 r angle)
          size (+ 8 (* 13 (- 1 (/ i 90.0))))
          palette ["#ffd481" "#ffc45d" "#f4a63c" "#fbd996" "#e69731"]]
      (shape! (format "02 / golden-angle luminary %02d" i)
              [(hex x y size)] (nth palette (mod i (count palette)))
              {"color" "#fff0bd" "width" 1 "opacity" 65})))
  (screenshot! "stage-03-spiral.png")
  ;; The circular construction echoes the Flower of Life; concentric hex frames.
  (doseq [r [115 180 275 385]]
    (shape! (str "03 / sacred hexagon · radius " r) [(hex 800 500 r)] nil
            {"color" "#f5d695" "width" (if (= r 115) 3 1.8) "opacity" (if (= r 115) 85 48)}))
  (doseq [i (range 6)]
    (let [[x y] (polar 800 500 112 (* i (/ tau 6)))]
      (circle! (str "04 / flower-of-life orbit " i) x y 112 nil
               {"color" "#eed69b" "width" 1.4 "opacity" 50})))
  (circle! "05 / solar halo" 800 500 88 nil {"color" "#fff0c4" "width" 3 "opacity" 85})
  (circle! "05 / inner amber sun" 800 500 55
           {"gradient" {"style" "radial" "stops" [[0 "#fff5c8"] [0.54 "#ffd369"] [1 "#e99830"]]}}
           {"color" "#fff6db" "width" 4})
  (shape! "05 / sun crystal" [(hex 800 500 31)] "#172635"
          {"color" "#fff1c6" "width" 2.2})
  (doseq [a (range 0 12)]
    (let [theta (* a (/ tau 12))]
      (line! (str "05 / ray " a) (mapv #(polar 800 500 % theta) [63 81]) "#ffdf91" 2)))
  (screenshot! "stage-04-geometry.png")
  ;; Generous editorial title, contrasting modern typography and fine-rule accents.
  (exec! "type.create" {"x" 95 "y" 136 "text" "HIVE  ·  GEOMETRY"
                         "name" "06 / title" "font" "DejaVu Sans" "size" 60
                         "color" "#fff0cc"})
  (exec! "type.create" {"x" 101 "y" 181 "text" "THE GOLDEN ANGLE  /  137.5°"
                         "name" "06 / subtitle" "font" "DejaVu Sans" "size" 19
                         "color" "#eabf77"})
  (line! "06 / left rule" [[100 202] [537 202]] "#f2c47a" 2)
  (exec! "type.create" {"x" 107 "y" 930 "text" "NATURE BUILDS IN SIXES.  LIGHT MOVES IN SPIRALS."
                         "name" "06 / footer" "font" "DejaVu Sans" "size" 19
                         "color" "#f2d29b"})
  (line! "06 / footer rule" [[105 894] [1495 894]] "#dfb46b" 1.5)
  (screenshot! "stage-05-final.png")
  (call! "app.save" {"path" "hive-geometry.pcraft"})
  (call! "app.save" {"path" "hive-geometry.png"})
  (screenshot! "hive-geometry-window.png")
  (select-keys (exec! "document.inspect" {}) ["name" "width" "height" "layers"]))
