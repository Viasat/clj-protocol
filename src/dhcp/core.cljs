(ns dhcp.core
  (:require [protocol.fields :as fields]
            [protocol.addrs :as addrs]
            [protocol.tlvs :as tlvs]
            [protocol.header :as header]))

(def MAX-BUF-SIZE 1500)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DHCP spec defined values (uppercased)

(def RECV-PORT 67)
(def SEND-PORT 68)

;; https://datatracker.ietf.org/doc/html/rfc3046
(def OPTS-RELAY-AGENT-LIST
  ;; code, name, type
  [[0x01  :circuit-id     :raw   ]
   [0x02  :remote-id      :raw   ]
   [0x06  :subscriber-id  :str   ]])
(def OPTS-RELAY-AGENT-LOOKUP (tlvs/tlv-list->lookup OPTS-RELAY-AGENT-LIST))

(def OPTS-ETHERBOOT-LIST
  ;; code, name, type
  [[0x01  :eb-priority    :uint8 ]
   [0x08  :eb-yi-addr     :raw   ]
   [0x51  :eb-scriptlet   :raw   ]
   [0xb2  :eb-use-cached  :uint8 ]
   [0xbe  :eb-username    :str   ]
   [0xbf  :eb-password    :str   ]])
(def OPTS-ETHERBOOT-LOOKUP (tlvs/tlv-list->lookup OPTS-ETHERBOOT-LIST))

;; https://www.iana.org/assignments/bootp-dhcp-parameters/bootp-dhcp-parameters.xhtml
(def OPTS-LIST
  ;; code,  name,                type          extra context
  (into
    [[53  :opt/msg-type          :msg-type     nil] ;; Typically sent first
     [1   :opt/netmask           :ipv4         nil]
     [3   :opt/router            :ipv4         nil]
     [4   :opt/time-servers      :repeat       {:type :ipv4 :size 4}]
     [6   :opt/dns-servers       :repeat       {:type :ipv4 :size 4}]
     [12  :opt/hostname          :str          nil]
     [15  :opt/domainname        :str          nil]
     [28  :opt/mtu               :uint16       nil]
     [28  :opt/broadcast         :ipv4         nil]
     [41  :opt/nis-servers       :repeat       {:type :ipv4 :size 4}]
     [43  :opt/vend-spec-info    :raw          nil]
     [50  :opt/addr-req          :ipv4         nil]
     [51  :opt/lease-time        :uint32       nil]
     [54  :opt/dhcp-server-id    :ipv4         nil]
     [55  :opt/parm-list         :raw          nil]
     [58  :opt/renew-time        :uint32       nil]
     [59  :opt/rebind-time       :uint32       nil]
     [60  :opt/vendor-class-id   :raw          nil]
     [61  :opt/client-id         :raw          nil]
     [67  :opt/bootfile          :str          nil]
     [82  :opt/relay-agent-info  :tlv-map      {:lookup OPTS-RELAY-AGENT-LOOKUP}]
     [97  :opt/guid              :raw          nil]
     [175 :opt/etherboot         :tlv-map      {:lookup OPTS-ETHERBOOT-LOOKUP}]]

    (concat
      ;; RFC-3942 site-specific options (224-254)
      (map (fn [n]
             [n (keyword (str "opt-site-" n)) :raw])
           (range 224 (inc 254)))

      [[255 :opt/end             :stop        nil ]])))
(def OPTS-LOOKUP (tlvs/tlv-list->lookup OPTS-LIST))



;; https://datatracker.ietf.org/doc/html/rfc2131
(def DHCP-FLAGS [[:broadcast  :bool   1]
                 [:reserved   :int   15]])

(def DHCP-HEADER
;;  name,          type,    length,  default,                 extra-context
  [[:op            :uint8        1     0                      nil]
   [:htype         :uint8        1     1                      nil]
   [:hlen          :uint8        1     6                      nil]
   [:hops          :uint8        1     0                      nil]
   [:xid           :uint32       4     0                      nil]
   [:secs          :uint16       2     0                      nil]
   [:flags         :bitfield     2     nil                    {:spec DHCP-FLAGS}]
   [:ciaddr        :ipv4         4     "0.0.0.0"              nil]
   [:yiaddr        :ipv4         4     "0.0.0.0"              nil]
   [:siaddr        :ipv4         4     "0.0.0.0"              nil] ;; next server
   [:giaddr        :ipv4         4     "0.0.0.0"              nil]
   [:chaddr        :mac          6     "00:00:00:00:00:00"    nil]
   [:chaddr-extra  :raw          10    [0 0 0 0 0 0 0 0 0 0]  nil]
   [:sname         :str          64    ""                     nil]
   [:bootfile      :str          128   ""                     nil] ;; :file
   [:cookie        :raw          4     [99 130 83 99]         nil]
   [:options       :tlv-map      :*    nil                    {:tlv-tsize 1
                                                               :tlv-lsize 1
                                                               :lookup OPTS-LOOKUP}]])

(def HEADERS-FIXED {:htype  1
                    :hlen   6
                    :hops   0 ;; fixed until relay supported
                    :cookie [99 130 83 99]}) ;; 0x63825363

(def MSG-TYPE-LIST [;; num,  message, resp, broadcast
                    [1   :DISCOVER     :OFFER true]
                    [2   :OFFER        nil    nil]
                    [3   :REQUEST      :ACK   true]
                    [4   :DECLINE      nil    nil] ;; We don't currently handle decline
                    [5   :ACK          nil    nil]
                    [6   :NAK          nil    nil]
                    [7   :RELEASE      :ACK   false]
                    [8   :INFORM       :ACK   false]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generated from protocol

(def DHCP-DEFAULTS
  (into {} (for [[fname  _ _ fdefault _] DHCP-HEADER
                 :when fdefault]
             [fname fdefault])))

(def MSG-TYPE-LOOKUP
  (merge (into {} (map (fn [[n m r b]] [n m]) MSG-TYPE-LIST))
         (into {} (map (fn [[n m r b]] [m n]) MSG-TYPE-LIST))))
(def MSG-TYPE-RESP-LOOKUP
  (into {} (map (fn [[n m r b]] [m r]) MSG-TYPE-LIST)))
(def MSG-TYPE-BCAST-LOOKUP
  (into {} (map (fn [[n m r b]] [m b]) MSG-TYPE-LIST)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General DHCP message reading/writing
(set! *warn-on-infer* false)

(def readers
  (merge
    fields/readers
    addrs/readers
    tlvs/readers
    {:msg-type #(get MSG-TYPE-LOOKUP (.readUInt8 %1 %2))}))

(def writers
  (merge
    fields/writers
    addrs/writers
    tlvs/writers
    {:msg-type #(.writeUInt8 %1 (get MSG-TYPE-LOOKUP %2) %3)}))

(set! *warn-on-infer* true)

;;;


(defn read-dhcp [buf]
  ;; Merge options up into the top level map
  (let [msg-map (header/read-header buf 0 (.-length buf)
                                    {:readers readers :spec DHCP-HEADER})
        options (:options msg-map)]
    (dissoc (merge msg-map options)
            :options
            :opt/end)))

(defn write-dhcp [msg-map]
  ;; Move options down into :options keys
  (let [options (into {:opt/end 0}
                      (for [fname (map second OPTS-LIST)
                            :when (contains? msg-map fname)]
                        [fname (get msg-map fname)]))
        msg-map (merge msg-map HEADERS-FIXED {:options options})
        buf (.alloc js/Buffer MAX-BUF-SIZE)]
    (header/write-header buf msg-map 0
                         {:writers writers :spec DHCP-HEADER})))


(defn default-response [msg-map srv-if]
  (let [msg-type (:opt/msg-type msg-map)]
    (merge
      DHCP-DEFAULTS
      (select-keys msg-map [:xid :secs :chaddr])
      {:op                 2 ;; DHCP response
       :flags              {:broadcast (get MSG-TYPE-BCAST-LOOKUP msg-type)
                            :reserved 0}
       :opt/msg-type       (get MSG-TYPE-RESP-LOOKUP msg-type)
       :opt/lease-time     (* 60 60 24) ;; default to 1 day
       :siaddr             (:address srv-if)
       :opt/netmask        (:netmask srv-if)
       :opt/router         (:address srv-if)
       :opt/dhcp-server-id (:address srv-if)
       :opt/broadcast      (:broadcast srv-if)
       :opt/dns-servers    [(:address srv-if)]})))

