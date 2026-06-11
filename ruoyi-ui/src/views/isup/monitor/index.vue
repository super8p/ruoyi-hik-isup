<template>
  <div class="monitor-container">
    <el-row :gutter="20" class="monitor-layout">
      <!-- 左侧：设备与通道树 -->
      <el-col :span="6" class="panel-left">
        <div class="glass-card full-height">
          <div class="panel-header">
            <h3><i class="el-icon-video-camera"></i> ISUP 设备列表</h3>
            <el-button icon="Refresh" circle size="small" @click="fetchDevices" />
          </div>
          <el-scrollbar>
            <div v-if="devices.length === 0" class="empty-state">
              暂无注册设备，等待设备上线...
            </div>
            <div v-for="dev in devices" :key="dev.deviceId" class="device-item">
              <div class="device-info" @click="toggleDevice(dev)">
                <span class="status-dot" :class="{ online: dev.isOnline === 1 }"></span>
                <span class="device-name">ID: {{ dev.deviceId }}</span>
                <span class="device-tag">{{ dev.isOnline === 1 ? '在线' : '离线' }}</span>
              </div>
              <div v-if="expandedDevices.includes(dev.deviceId)" class="channel-list">
                <div 
                  v-for="ch in dev.channels" 
                  :key="ch.channelId" 
                  class="channel-item"
                  :class="{ active: selectedChannel?.channelId === ch.channelId && selectedDevice?.deviceId === dev.deviceId }"
                  @click="selectChannel(dev, ch)"
                >
                  <el-icon><VideoCamera /></el-icon>
                  <span>通道 {{ ch.channelId }}</span>
                </div>
              </div>
            </div>
          </el-scrollbar>
        </div>
      </el-col>

      <!-- 中间：WebRTC 播放器与回放控制 -->
      <el-col :span="12" class="panel-center">
        <div class="glass-card full-height player-panel">
          <div class="panel-header">
            <h3>
              <span v-if="playMode === 'live'"><el-badge is-dot type="danger">实时预览</el-badge></span>
              <span v-else><el-badge is-dot type="warning">历史回放</el-badge></span>
              - {{ selectedDevice ? selectedDevice.deviceId : '请选择通道' }}
            </h3>
            <div class="mode-switch" style="display: flex; gap: 10px; align-items: center;">
              <el-radio-group v-model="streamType" size="small" @change="handleStreamTypeChange">
                <el-radio-button label="flv">HTTP-FLV</el-radio-button>
                <el-radio-button label="webrtc">WebRTC</el-radio-button>
              </el-radio-group>
              <el-radio-group v-model="playMode" size="small" @change="handleModeChange">
                <el-radio-button label="live">实时</el-radio-button>
                <el-radio-button label="playback">回放</el-radio-button>
              </el-radio-group>
            </div>
          </div>

          <!-- 播放器视图 -->
          <div class="video-wrapper">
            <video 
              id="webrtc-video" 
              ref="videoRef"
              autoplay 
              controls 
              playsinline
              class="video-player"
            ></video>
            <div v-if="loading" class="video-overlay">
              <el-icon class="is-loading"><Loading /></el-icon>
              <span v-if="streamType === 'webrtc'">正在获取视频流并进行 WebRTC 握手...</span>
              <span v-else>正在获取视频流并初始化 FLV 播放器...</span>
            </div>
            <div v-if="!selectedChannel && !loading" class="video-overlay placeholder-overlay">
              <el-icon size="48"><VideoPlay /></el-icon>
              <span>选择左侧通道开始播放</span>
            </div>
          </div>

          <!-- 回放时间控制 -->
          <div v-if="playMode === 'playback'" class="playback-controls">
            <el-date-picker
              v-model="playbackTimeRange"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="YYYY-MM-DD HH:mm:ss"
              size="small"
              class="time-picker"
            />
            <el-button type="warning" size="small" icon="VideoPlay" @click="startPlaybackStream" :disabled="!selectedChannel">
              启动回放
            </el-button>
          </div>

          <!-- 流地址与状态调试 -->
          <div class="stream-info-debug" v-if="debugInfo">
            <p><strong>FLV URL:</strong> {{ debugInfo.httpFlv }}</p>
            <p><strong>WebRTC URL:</strong> {{ debugInfo.webrtc }}</p>
          </div>
        </div>
      </el-col>

      <!-- 右侧：云台控制与 TTS 广播 -->
      <el-col :span="6" class="panel-right">
        <div class="glass-card full-height right-controls">


          <!-- TTS 语音广播 -->
          <div class="voice-section">
            <h4>TTS 语音播报</h4>
            <el-input
              v-model="ttsText"
              type="textarea"
              :rows="3"
              placeholder="请输入广播内容，支持中文朗读"
              class="tts-input"
            />
            <el-button 
              type="primary" 
              class="voice-btn" 
              icon="Mic" 
              @click="sendTts" 
              :loading="sendingTts"
              :disabled="!selectedDevice"
            >
              发送播报
            </el-button>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 底部：实时消防告警记录 -->
    <el-row class="alarm-row">
      <el-col :span="24">
        <div class="glass-card alarm-panel">
          <div class="panel-header">
            <h3><el-icon color="#f56c6c"><BellFilled /></el-icon> 实时消防告警（动态接入）</h3>
            <span class="sse-status" :class="{ connected: sseConnected }">
              {{ sseConnected ? '实时监听中 (SSE)' : '正在连接告警通道...' }}
            </span>
          </div>
          <div class="alarm-logs">
            <el-table :data="alarmLogs" size="small" stripe style="width: 100%" max-height="200" class="dark-table">
              <el-table-column prop="time" label="报警时间" width="180" />
              <el-table-column prop="deviceId" label="设备 ID" width="180" />
              <el-table-column prop="type" label="事件类型" width="150">
                <template #default="scope">
                  <el-tag type="danger" size="small" effect="dark">{{ scope.row.type }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="message" label="告警详情" />
            </el-table>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import request from '@/utils/request';
import mpegts from 'mpegts.js';
import { ElMessage, ElNotification } from 'element-plus';
import { 
  VideoCamera, 
  VideoPlay, 
  Loading, 
  Mic,
  BellFilled 
} from '@element-plus/icons-vue';

// 页面数据
const devices = ref([]);
const expandedDevices = ref([]);
const selectedDevice = ref(null);
const selectedChannel = ref(null);
const playMode = ref('live'); // live | playback
const streamType = ref('flv'); // flv | webrtc
const loading = ref(false);
const debugInfo = ref(null);

// 回放控制
const playbackTimeRange = ref([]);

// 语音 & 云台
const ttsText = ref('检测到烟雾，请尽快撤离！');
const sendingTts = ref(false);
const videoRef = ref(null);

// WebRTC 状态
let peerConnection = null;
let flvPlayer = null;

// 实时消防告警
const alarmLogs = ref([]);
const sseConnected = ref(false);
let sseSource = null;

// API 请求封装
const getDevicesApi = () => request({ url: '/api/devices', method: 'get' });
const startPreviewApi = (devId, chId) => request({ url: `/api/devices/${devId}/preview?channelId=${chId}`, method: 'post' });
const stopPreviewApi = (devId, chId) => request({ url: `/api/devices/${devId}/preview?channelId=${chId}`, method: 'delete' });
const startPlaybackApi = (devId, chId, start, end) => request({ 
  url: `/api/devices/${devId}/playback?channelId=${chId}&startTime=${encodeURIComponent(start)}&endTime=${encodeURIComponent(end)}`, 
  method: 'post' 
});
const stopPlaybackApi = (devId, chId) => request({ url: `/api/devices/${devId}/playback?channelId=${chId}`, method: 'delete' });

const sendTtsApi = (devId, text) => request({ 
  url: `/api/devices/${devId}/voice/tts`, 
  method: 'post', 
  data: { text } 
});

// 获取设备列表
const fetchDevices = async () => {
  try {
    const res = await getDevicesApi();
    if (res.code === 200 || res.code === 0) {
      devices.value = res.data || res.rows || [];
    } else {
      ElMessage.error(res.msg || '获取设备列表失败');
    }
  } catch (err) {
    console.error(err);
    ElMessage.error('获取设备列表出错');
  }
};

const toggleDevice = (dev) => {
  const index = expandedDevices.value.indexOf(dev.deviceId);
  if (index > -1) {
    expandedDevices.value.splice(index, 1);
  } else {
    expandedDevices.value.push(dev.deviceId);
  }
};

const selectChannel = (dev, ch) => {
  selectedDevice.value = dev;
  selectedChannel.value = ch;
  
  if (playMode.value === 'live') {
    startLiveStream();
  }
};

// 切换播放模式
const handleModeChange = () => {
  closeStream();
  debugInfo.value = null;
  if (playMode.value === 'live' && selectedChannel.value) {
    startLiveStream();
  }
};

// 切换播放协议
const handleStreamTypeChange = () => {
  closeStream();
  if (!selectedChannel.value) return;
  if (playMode.value === 'live') {
    startLiveStream();
  } else {
    startPlaybackStream();
  }
};

// 关闭当前流连接
const closeStream = () => {
  if (peerConnection) {
    peerConnection.close();
    peerConnection = null;
  }
  if (flvPlayer) {
    try {
      flvPlayer.pause();
      flvPlayer.unload();
      flvPlayer.detachMediaElement();
      flvPlayer.destroy();
    } catch (e) {
      console.error('销毁 FLV 播放器出错:', e);
    }
    flvPlayer = null;
  }
  if (videoRef.value) {
    videoRef.value.srcObject = null;
    videoRef.value.src = '';
  }
  loading.value = false;
};

// FLV 播放逻辑
const startFLV = (flvUrl) => {
  closeStream();
  loading.value = true;

  if (mpegts.isSupported()) {
    try {
      flvPlayer = mpegts.createPlayer({
        type: 'flv',
        url: flvUrl,
        isLive: true,
        hasAudio: false
      }, {
        enableWorker: true,
        enableStashBuffer: false,
        stashInitialSize: 128
      });
      flvPlayer.attachMediaElement(videoRef.value);
      flvPlayer.load();
      flvPlayer.play();

      flvPlayer.on(mpegts.Events.ERROR, (type, detail, info) => {
        console.error('FLV error:', type, detail, info);
        ElMessage.error('FLV 播放错误');
      });

      loading.value = false;
      ElMessage.success('FLV 连接成功，开始播放');
    } catch (err) {
      console.error('FLV 初始化失败:', err);
      closeStream();
      ElMessage.error('视频流加载失败，请检查流媒体服务连接');
    }
  } else {
    ElMessage.error('当前浏览器不支持 FLV 播放');
  }
};

// WebRTC 协商握手逻辑
const startWebRTC = async (webrtcUrl) => {
  closeStream();
  loading.value = true;

  try {
    // 1. 创建 RTCPeerConnection 实例
    peerConnection = new RTCPeerConnection({
      iceServers: []
    });

    // 2. 添加仅接收音频和视频的 Transceiver
    peerConnection.addTransceiver('video', { direction: 'recvonly' });
    peerConnection.addTransceiver('audio', { direction: 'recvonly' });

    // 3. 监听轨道添加
    peerConnection.ontrack = (event) => {
      console.log('收到媒体流轨道：', event.streams);
      if (videoRef.value) {
        videoRef.value.srcObject = event.streams[0];
        videoRef.value.play().catch(err => {
          console.warn('视频自动播放被浏览器拦截，需要用户交互:', err);
        });
      }
    };

    // 4. 生成本地 SDP Offer
    const offer = await peerConnection.createOffer();
    await peerConnection.setLocalDescription(offer);

    // 5. 通过后端代理与 ZLMediaKit 信令交换 (POST Offer SDP)
    const proxyUrl = `/api/devices/${selectedDevice.value.deviceId}/webrtcSdp?targetUrl=${encodeURIComponent(webrtcUrl)}`;
    const response = await request({
      url: proxyUrl,
      method: 'POST',
      headers: {
        'Content-Type': 'text/plain'
      },
      data: offer.sdp
    });

    let answerSdp = "";
    if (response && response.code === 200) {
      answerSdp = response.data;
    } else {
      throw new Error(response ? response.msg : '代理协商失败');
    }

    if (!answerSdp || answerSdp.indexOf('v=') !== 0) {
      throw new Error('ZLMediaKit 返回的 WebRTC 信令格式错误: ' + answerSdp);
    }

    // 6. 应用 ZLMediaKit 返回 of the Answer SDP
    await peerConnection.setRemoteDescription(new RTCSessionDescription({
      type: 'answer',
      sdp: answerSdp
    }));

    loading.value = false;
    ElMessage.success('WebRTC 连接建立成功，开始播放');
  } catch (err) {
    console.error('WebRTC 握手失败：', err);
    closeStream();
    ElMessage.error('视频流加载失败，请检查流媒体服务连接');
  }
};

// 实时预览
const startLiveStream = async () => {
  if (!selectedDevice.value || !selectedChannel.value) return;
  loading.value = true;
  
  try {
    const res = await startPreviewApi(selectedDevice.value.deviceId, selectedChannel.value.channelId);
    if (res.code === 200 || res.code === 0) {
      debugInfo.value = res.data;
      if (streamType.value === 'webrtc') {
        if (res.data.webrtc) {
          startWebRTC(res.data.webrtc);
        } else {
          ElMessage.warning('后台未返回 WebRTC 播放地址');
          loading.value = false;
        }
      } else {
        if (res.data.httpFlv) {
          startFLV(res.data.httpFlv);
        } else {
          ElMessage.warning('后台未返回 FLV 播放地址');
          loading.value = false;
        }
      }
    } else {
      ElMessage.error(res.msg || '预览开启失败');
      loading.value = false;
    }
  } catch (err) {
    console.error(err);
    loading.value = false;
  }
};

// 历史回放
const startPlaybackStream = async () => {
  if (!selectedDevice.value || !selectedChannel.value) return;
  if (!playbackTimeRange.value || playbackTimeRange.value.length < 2) {
    ElMessage.warning('请先选择回放的时间范围');
    return;
  }
  loading.value = true;
  const start = playbackTimeRange.value[0];
  const end = playbackTimeRange.value[1];

  try {
    const res = await startPlaybackApi(selectedDevice.value.deviceId, selectedChannel.value.channelId, start, end);
    if (res.code === 200 || res.code === 0) {
      debugInfo.value = res.data;
      if (streamType.value === 'webrtc') {
        if (res.data.webrtc) {
          startWebRTC(res.data.webrtc);
        } else {
          ElMessage.warning('后台未返回 WebRTC 播放地址');
          loading.value = false;
        }
      } else {
        if (res.data.httpFlv) {
          startFLV(res.data.httpFlv);
        } else {
          ElMessage.warning('后台未返回 FLV 播放地址');
          loading.value = false;
        }
      }
    } else {
      ElMessage.error(res.msg || '开启回放失败');
      loading.value = false;
    }
  } catch (err) {
    console.error(err);
    loading.value = false;
  }
};



// 发送 TTS 广播
const sendTts = async () => {
  if (!selectedDevice.value) {
    ElMessage.warning('请选择一个注册设备');
    return;
  }
  if (!ttsText.value.trim()) {
    ElMessage.warning('广播内容不能为空');
    return;
  }
  sendingTts.value = true;
  try {
    const res = await sendTtsApi(selectedDevice.value.deviceId, ttsText.value);
    if (res.code === 200 || res.code === 0) {
      ElMessage.success('语音播报成功，已传输至设备端');
    } else {
      ElMessage.error(res.msg || '语音播报失败');
    }
  } catch (err) {
    console.error(err);
  } finally {
    sendingTts.value = false;
  }
};

// 初始化实时告警监听 (SSE)
const initSse = () => {
  const sseUrl = '/dev-api/api/alarms/sse';
  console.log('连接告警信道 SSE:', sseUrl);

  try {
    sseSource = new EventSource(sseUrl);

    sseSource.onopen = () => {
      sseConnected.value = true;
      console.log('告警 SSE 连接成功');
    };

    sseSource.onerror = (e) => {
      sseConnected.value = false;
      console.error('告警 SSE 连接断开，尝试自动重连...', e);
    };

    sseSource.addEventListener('alarm', (event) => {
      try {
        const data = JSON.parse(event.data);
        console.log('接收到告警数据:', data);
        
        let deviceId = '未知设备';
        let detail = data.message;
        const devMatch = data.message.match(/DeviceID:([^\s,]+)/);
        if (devMatch) {
          deviceId = devMatch[1];
        }

        const newLog = {
          time: new Date().toLocaleString(),
          deviceId: deviceId,
          type: data.type,
          message: detail
        };

        alarmLogs.value.unshift(newLog);

        ElNotification({
          title: 'ISUP 设备告警触发',
          message: `设备 [${deviceId}] 触发告警：${newLog.message.substring(0, 100)}...`,
          type: 'error',
          duration: 10000,
          position: 'top-right'
        });
      } catch (err) {
        console.error('解析告警消息失败', err);
      }
    });

  } catch (err) {
    console.error('初始化 SSE 告警监听失败:', err);
  }
};

// 钩子
onMounted(() => {
  fetchDevices();
  initSse();
});

onUnmounted(() => {
  closeStream();
  if (sseSource) {
    sseSource.close();
  }
});
</script>

<style scoped>
.monitor-container {
  padding: 20px;
  background: #0f0f1a;
  min-height: calc(100vh - 84px);
  color: #e2e8f0;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.monitor-layout {
  height: 60vh;
  margin-bottom: 20px;
}

.full-height {
  height: 100%;
}

.glass-card {
  background: rgba(30, 30, 45, 0.65);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.3);
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  padding-bottom: 10px;
  margin-bottom: 15px;
}

.panel-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 设备树样式 */
.empty-state {
  text-align: center;
  color: #64748b;
  margin-top: 50px;
  font-size: 14px;
}

.device-item {
  margin-bottom: 10px;
}

.device-info {
  display: flex;
  align-items: center;
  padding: 10px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1px solid transparent;
}

.device-info:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.1);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 10px;
  background: #94a3b8;
}

.status-dot.online {
  background: #10b981;
  box-shadow: 0 0 8px #10b981;
}

.device-name {
  flex: 1;
  font-size: 13px;
  color: #f1f5f9;
  font-weight: 500;
}

.device-tag {
  font-size: 11px;
  background: rgba(255, 255, 255, 0.06);
  padding: 2px 6px;
  border-radius: 4px;
  color: #94a3b8;
}

.channel-list {
  padding-left: 18px;
  margin-top: 5px;
  border-left: 1px dashed rgba(255, 255, 255, 0.1);
}

.channel-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  font-size: 12px;
  color: #cbd5e1;
  cursor: pointer;
  border-radius: 4px;
  margin-top: 3px;
  transition: all 0.2s ease;
}

.channel-item:hover {
  background: rgba(255, 255, 255, 0.05);
  color: #fff;
}

.channel-item.active {
  background: rgba(59, 130, 246, 0.2);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

/* 播放器样式 */
.player-panel {
  justify-content: space-between;
}

.video-wrapper {
  position: relative;
  flex: 1;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.04);
}

.video-player {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.video-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(10, 10, 15, 0.85);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 15px;
  color: #94a3b8;
  font-size: 14px;
}

.placeholder-overlay {
  background: #09090e;
  color: #475569;
}

.playback-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  background: rgba(255, 255, 255, 0.03);
  padding: 8px;
  border-radius: 6px;
}

.time-picker {
  flex: 1;
}

.stream-info-debug {
  margin-top: 10px;
  font-size: 11px;
  color: #64748b;
  word-break: break-all;
  background: rgba(0, 0, 0, 0.2);
  padding: 6px;
  border-radius: 4px;
}

/* 云台与语音样式 */
.right-controls {
  justify-content: flex-start;
  gap: 24px;
}

.voice-section {
  display: flex;
  flex-direction: column;
}

.voice-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  color: #cbd5e1;
  font-weight: 600;
}

.tts-input {
  margin-bottom: 10px;
}

.voice-btn {
  width: 100%;
}

/* 告警面板样式 */
.alarm-row {
  height: 25vh;
}

.alarm-panel {
  height: 100%;
}

.sse-status {
  font-size: 12px;
  color: #f59e0b;
}

.sse-status.connected {
  color: #10b981;
  text-shadow: 0 0 4px rgba(16, 185, 129, 0.4);
}

.alarm-logs {
  flex: 1;
  overflow: hidden;
}

/* 暗色表格定制 */
:deep(.dark-table) {
  background-color: transparent !important;
  color: #e2e8f0 !important;
}

:deep(.dark-table th.el-table__cell) {
  background-color: rgba(255, 255, 255, 0.04) !important;
  color: #cbd5e1 !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08) !important;
}

:deep(.dark-table tr) {
  background-color: transparent !important;
}

:deep(.dark-table td.el-table__cell) {
  border-bottom: 1px solid rgba(255, 255, 255, 0.04) !important;
  color: #cbd5e1 !important;
}

:deep(.el-table--striped .el-table__body tr.el-table__row--striped td.el-table__cell) {
  background-color: rgba(255, 255, 255, 0.02) !important;
}

:deep(.el-table__body tr:hover > td.el-table__cell) {
  background-color: rgba(255, 255, 255, 0.05) !important;
}
</style>