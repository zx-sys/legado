import { ElNotification as message } from 'element-plus';
import { getToken } from "@/utils/util";
import sysConfig from "@/config"
import { useCcWebsocket } from "@/store/websocket";

function getJWTAuthorization() {
    const token = getToken();
    const jwt = 'JWTlybbn' + token;
    return jwt;
}

const lyWebSocket = {
    websocket:null,
    socketOpen:false,
    heartbeatTimer:null,
    heartbeatInterval:10 * 1000,
    isReconnect :true,
    reconnectCount:3,
    reconnectCurrent:1,
    reconnectTimer:null,
    reconnectInterval:5 * 1000,

    initWebSocket(revMessage){
        let domain = sysConfig.API_HOST
        if (!('WebSocket' in window)) {
            message.warning('该浏览器不支持WebSocket');
            return null;
        }

        const token = getToken();
        if (!token) {
            return null;
        }

        const wsUrl = (window.location.protocol === 'http:' ? 'ws://' : 'wss://') + `${domain}/ws/msg/`;
        lyWebSocket.websocket = new WebSocket(wsUrl, ['JWTLYADMIN', getJWTAuthorization()]);

        lyWebSocket.websocket.onmessage = (e) => {
            if (revMessage) {
                revMessage(e);
            }
        };

        lyWebSocket.websocket.onclose = (e) => {
            lyWebSocket.socketOpen = false;
            useCcWebsocket().setWebSocketState(lyWebSocket.socketOpen);

            if (lyWebSocket.isReconnect) {
                lyWebSocket.reconnectTimer = setTimeout(() => {
                    if (lyWebSocket.reconnectCurrent > lyWebSocket.reconnectCount) {
                        clearTimeout(lyWebSocket.reconnectTimer);
                        lyWebSocket.isReconnect = false;
                        lyWebSocket.socketOpen = false;
                        useCcWebsocket().setWebSocketState(lyWebSocket.socketOpen);
                        return;
                    }

                    lyWebSocket.reconnectCurrent++;
                    lyWebSocket.reconnectWebSocket(revMessage); // 传递 revMessage 参数
                }, lyWebSocket.reconnectInterval);
            }
        };

        lyWebSocket.websocket.onopen = () => {
            lyWebSocket.socketOpen = true;
            useCcWebsocket().setWebSocketState(lyWebSocket.socketOpen);
            lyWebSocket.isReconnect = true;
            lyWebSocket.startHeartbeat();
        };

        lyWebSocket.websocket.onerror = (e) => {
        };
    },
    startHeartbeat(){
        if (lyWebSocket.heartbeatTimer) {
            clearInterval(lyWebSocket.heartbeatTimer);
        }

        lyWebSocket.heartbeatTimer = setInterval(() => {
            const data = {
                time: new Date().getTime()
            };
            lyWebSocket.sendWebSocketMessage(data);
        }, lyWebSocket.heartbeatInterval);
    },
    sendWebSocketMessage(data, callback = null){
        if (lyWebSocket.websocket && lyWebSocket.websocket.readyState === lyWebSocket.websocket.OPEN) {
            lyWebSocket.websocket.send(JSON.stringify(data));
            callback && callback();
        } else {
            clearInterval(lyWebSocket.heartbeatTimer);
            lyWebSocket.socketOpen = false;
            useCcWebsocket().setWebSocketState(lyWebSocket.socketOpen);
        }
    },
    closeWebSocket(){
        lyWebSocket.isReconnect = false;
        lyWebSocket.websocket && lyWebSocket.websocket.close();
        lyWebSocket.websocket = null;
        lyWebSocket.socketOpen = false;
        useCcWebsocket().setWebSocketState(lyWebSocket.socketOpen);
    },
    reconnectWebSocket(){
        if (lyWebSocket.websocket && !lyWebSocket.isReconnect) {
            lyWebSocket.closeWebSocket();
        }
        lyWebSocket.initWebSocket(null);
    }
};

export default lyWebSocket
