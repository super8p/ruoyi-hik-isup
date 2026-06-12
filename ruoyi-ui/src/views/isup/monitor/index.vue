<template>
  <div class="monitor-container">
    <el-row :gutter="20" class="monitor-layout">
      <!-- 左侧：设备与通道树 -->
      <el-col :span="6" class="panel-left">
        <div class="glass-card full-height">
          <div class="panel-header">
            <h3><i class="el-icon-video-camera"></i> 设备列表</h3>
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
              <span>实时预览</span>
              - {{ selectedDevice ? selectedDevice.deviceId : '请选择通道' }}
            </h3>
          </div>

          <!-- 播放器视图 -->
          <div class="video-wrapper">
            <video 
              id="webrtc-video" 
              ref="videoRef"
              autoplay 
              playsinline
              class="video-player"
              @volumechange="handleVolumeChange"
            ></video>
            <div v-if="loading" class="video-overlay">
              <el-icon class="is-loading"><Loading /></el-icon>
              <span>正在获取视频流并进行 WebRTC 握手...</span>
            </div>
            <div v-if="!selectedChannel && !loading" class="video-overlay placeholder-overlay">
              <el-icon size="48"><VideoPlay /></el-icon>
              <span>选择左侧通道开始播放</span>
            </div>
            <!-- 自定义视频播放控制栏 (仿海康客户端样式) -->
            <div v-if="selectedChannel && !loading" class="custom-player-controls">
              <!-- 左侧控件组 -->
              <div class="controls-left">
                <!-- 音量控制组（静音+滑块+数字显示） -->
                <div class="volume-control-container">
                  <button class="control-btn" @click="toggleMute" :title="isMuted ? '恢复声音' : '静音'">
                    <svg v-if="isMuted" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M11 5L6 9H2v6h4l5 4V5z"/>
                      <line x1="23" y1="9" x2="17" y2="15"/>
                      <line x1="17" y1="9" x2="23" y2="15"/>
                    </svg>
                    <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M11 5L6 9H2v6h4l5 4V5z"/>
                      <path d="M15.54 8.46a5 5 0 0 1 0 7.07"/>
                      <path d="M19.07 4.93a10 10 0 0 1 0 14.14"/>
                    </svg>
                  </button>
                  <input 
                    type="range" 
                    min="0" 
                    max="100" 
                    v-model="volumeVal" 
                    class="volume-slider" 
                    @input="handleVolumeSliderInput"
                    title="音量调节"
                  />
                  <span class="volume-text">{{ isMuted ? 0 : volumeVal }}</span>
                </div>
                
                <!-- 截图 -->
                <button class="control-btn" @click="takeSnapshot" title="视频截图">
                  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/>
                    <circle cx="12" cy="13" r="4"/>
                  </svg>
                </button>
                
                <!-- 语音对讲 (带高亮框选中样式) -->
                <button 
                  class="control-btn talk-btn" 
                  :class="{ active: isIntercomActive, loading: connectingIntercom }" 
                  @click="toggleIntercom" 
                  title="语音对讲"
                >
                  <el-icon v-if="connectingIntercom" class="is-loading"><Loading /></el-icon>
                  <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
                    <path d="M19 10v1a7 7 0 0 1-14 0v-1"/>
                    <line x1="12" y1="18" x2="12" y2="23"/>
                    <line x1="8" y1="23" x2="16" y2="23"/>
                  </svg>
                </button>
              </div>
              
              <!-- 右侧 -->
              <div class="controls-right">
                <span class="live-badge">LIVE</span>
              </div>
            </div>
          </div>

        </div>
      </el-col>

      <!-- 右侧：云台控制与 TTS 广播 -->
      <el-col :span="6" class="panel-right">
        <div class="glass-card full-height right-controls">


          <!-- 音频消噪设置 -->
          <div class="audio-settings-section">
            <h4>音频消噪设置 (WebRTC)</h4>
            <div class="setting-item">
              <span class="setting-label">消噪滤波开关</span>
              <el-switch v-model="noiseFilterEnabled" />
            </div>
            <div class="setting-item">
              <div class="slider-header">
                <span>低通截止频率</span>
                <span class="slider-val">{{ filterFrequency }} Hz</span>
              </div>
              <el-slider 
                v-model="filterFrequency" 
                :min="1000" 
                :max="8000" 
                :step="100"
                :disabled="!noiseFilterEnabled"
              />
              <div class="setting-hint">说明: 降至 2500Hz-3000Hz 可有效去除啸叫与高频“飞机音”</div>
            </div>
            <div class="setting-item">
              <div class="slider-header">
                <span>人声增益强度</span>
                <span class="slider-val">{{ audioGain.toFixed(1) }}x</span>
              </div>
              <el-slider 
                v-model="audioGain" 
                :min="0.5" 
                :max="5.0" 
                :step="0.1"
              />
            </div>
          </div>

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
import { ref, watch, onMounted, onUnmounted } from 'vue';
import request from '@/utils/request';
import mpegts from 'mpegts.js';
import { ElMessage, ElNotification } from 'element-plus';
import { 
  VideoCamera, 
  VideoPlay, 
  Loading, 
  Mic,
  Mute,
  BellFilled 
} from '@element-plus/icons-vue';
import { resampleAndEncodeToAlaw } from '@/utils/audioTranscoder';

// 页面数据
const devices = ref([]);
const expandedDevices = ref([]);
const selectedDevice = ref(null);
const selectedChannel = ref(null);
const playMode = ref('live'); // live | playback
const streamType = ref('webrtc'); // webrtc only
const loading = ref(false);
const debugInfo = ref(null);

// 回放控制
const playbackTimeRange = ref([]);

// 语音 & 云台
const ttsText = ref('检测到烟雾，请尽快撤离！');
const sendingTts = ref(false);
const videoRef = ref(null);

// 音频处理相关
const audioGain = ref(1.0); // 增益默认 1.0
const noiseFilterEnabled = ref(true); // 噪声抑制开关
const filterFrequency = ref(3000); // 截止频率默认 3000Hz

const isMuted = ref(true); // 默认静音以保证浏览器能够自动播放
const volumeVal = ref(30); // 默认音量 30%

// 视频静音/恢复音量控制
const toggleMute = () => {
  isMuted.value = !isMuted.value;
  if (videoRef.value) {
    videoRef.value.muted = isMuted.value;
    if (!isMuted.value && volumeVal.value === 0) {
      volumeVal.value = 30;
    }
    videoRef.value.volume = isMuted.value ? 0 : volumeVal.value / 100;
  }
  if (gainNode) {
    if (isMuted.value) {
      gainNode.gain.value = 0;
    } else {
      gainNode.gain.value = (volumeVal.value / 100) * audioGain.value;
    }
  }
};

// 拖动音量条修改音量
const handleVolumeSliderInput = (e) => {
  const val = parseInt(e.target.value);
  volumeVal.value = val;
  if (videoRef.value) {
    videoRef.value.volume = val / 100;
    if (val > 0) {
      videoRef.value.muted = false;
      isMuted.value = false;
    } else {
      videoRef.value.muted = true;
      isMuted.value = true;
    }
  }
  if (gainNode) {
    if (isMuted.value) {
      gainNode.gain.value = 0;
    } else {
      gainNode.gain.value = (val / 100) * audioGain.value;
    }
  }
};


// 视频截图/快照
const takeSnapshot = () => {
  if (!videoRef.value) return;
  try {
    const canvas = document.createElement('canvas');
    canvas.width = videoRef.value.videoWidth || 1920;
    canvas.height = videoRef.value.videoHeight || 1080;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(videoRef.value, 0, 0, canvas.width, canvas.height);
    
    const dataUrl = canvas.toDataURL('image/png');
    const link = document.createElement('a');
    link.download = `snapshot_${selectedDevice.value?.deviceId || 'camera'}_${Date.now()}.png`;
    link.href = dataUrl;
    link.click();
    ElMessage.success('截图成功，已保存到本地');
  } catch (err) {
    console.error('截图失败:', err);
    ElMessage.error('截图失败，请确保视频正在播放');
  }
};

// 对讲状态与变量
const isIntercomActive = ref(false);
const connectingIntercom = ref(false);
let talkWs = null;
let talkAudioContext = null; // 独立对讲的 AudioContext，防止与播放器的 AudioContext 冲突
let audioContext = null;
let gainNode = null;
let filterNodes = []; // 低通滤波节点数组，串联连接以陡峭衰减高频飞机音/啸叫
let highpassNode = null; // 高通滤波节点，用于过滤低频交流电声/杂音
let audioDest = null;
let mediaStream = null;
let scriptProcessor = null;

// WebRTC 状态与音频处理
let peerConnection = null;
let flvPlayer = null;
let dummyAudio = null; // 用于在 Chrome 下激活/拉取 WebRTC 音频轨道
let audioSource = null;

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
  // 如果点击的是当前已经在播放/加载的通道，直接返回，不做任何操作，防止重复触发拉流与断流逻辑
  if (selectedDevice.value?.deviceId === dev.deviceId && selectedChannel.value?.channelId === ch.channelId) {
    return;
  }

  // 如果切换通道时已有旧的通道正在预览，立即关闭并清理旧视频流，防止音频在后台继续播放或残留
  if (selectedDevice.value && selectedChannel.value) {
    closeStream(true);
  }

  // 切换通道时，重置静音状态和音量为默认值
  isMuted.value = true;
  volumeVal.value = 30;

  selectedDevice.value = dev;
  selectedChannel.value = ch;
  
  if (playMode.value === 'live') {
    startLiveStream();
  }
};

// 切换播放模式
const handleModeChange = () => {
  closeStream(true);
  debugInfo.value = null;
  if (playMode.value === 'live' && selectedChannel.value) {
    startLiveStream();
  }
};

// 切换播放协议
const handleStreamTypeChange = () => {
  closeStream(true);
  if (!selectedChannel.value) return;
  if (playMode.value === 'live') {
    startLiveStream();
  } else {
    startPlaybackStream();
  }
};

// 关闭当前流连接
const closeStream = (shouldStopBackend = true) => {
  stopIntercom();

  // 主动通知后台关闭当前播放通道的预览流，释放设备与流媒体端口资源
  if (shouldStopBackend && selectedDevice.value && selectedChannel.value) {
    stopPreviewApi(selectedDevice.value.deviceId, selectedChannel.value.channelId).catch(e => {
      console.warn('通知后端关闭预览流失败:', e);
    });
  }

  if (peerConnection) {
    peerConnection.close();
    peerConnection = null;
  }
  if (dummyAudio) {
    try {
      dummyAudio.pause();
      dummyAudio.srcObject = null;
    } catch (e) {}
    dummyAudio = null;
  }
  if (audioSource) {
    try {
      audioSource.disconnect();
    } catch (e) {}
    audioSource = null;
  }
  if (gainNode) {
    try {
      gainNode.disconnect();
    } catch (e) {}
    gainNode = null;
  }
  if (filterNodes && filterNodes.length > 0) {
    filterNodes.forEach(node => {
      try {
        node.disconnect();
      } catch (e) {}
    });
    filterNodes = [];
  }
  if (highpassNode) {
    try {
      highpassNode.disconnect();
    } catch (e) {}
    highpassNode = null;
  }
  if (audioDest) {
    audioDest = null;
  }
  if (audioContext) {
    try {
      audioContext.close();
    } catch (e) {}
    audioContext = null;
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

// 语音对讲逻辑
const toggleIntercom = async () => {
  if (isIntercomActive.value) {
    stopIntercom();
  } else {
    await startIntercom();
  }
};

const startIntercom = async () => {
  if (!selectedDevice.value) {
    ElMessage.warning('请先选择设备通道');
    return;
  }
  connectingIntercom.value = true;
  try {
    // 1. 请求麦克风权限
    mediaStream = await navigator.mediaDevices.getUserMedia({ 
      audio: {
        echoCancellation: true, // 回声消除
        noiseSuppression: true, // 降噪
        autoGainControl: true    // 自动增益控制
      } 
    });

    // 2. 建立 WebSocket 对讲连接
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    const wsUrl = `${protocol}//${host}/dev-api/api/devices/${selectedDevice.value.deviceId}/talk`;
    
    talkWs = new WebSocket(wsUrl);
    talkWs.binaryType = 'arraybuffer';

    talkWs.onmessage = (event) => {
      if (event.data === 'READY') {
        startAudioCapture();
        isIntercomActive.value = true;
        connectingIntercom.value = false;
        ElNotification({
          title: '对讲已接通',
          message: '现在可以通过麦克风开始对讲了',
          type: 'success',
          duration: 3000
        });
      }
    };

    talkWs.onclose = (e) => {
      stopIntercom();
      if (e.reason) {
        ElMessage.warning(`对讲关闭: ${e.reason}`);
      } else {
        ElMessage.warning('对讲连接已断开');
      }
    };

    talkWs.onerror = (err) => {
      console.error('WebSocket talk error:', err);
      stopIntercom();
    };

  } catch (error) {
    console.error('开启麦克风或建立连接失败:', error);
    ElMessage.error('开启对讲失败: ' + (error.message || '麦克风权限被拒绝'));
    stopIntercom();
  }
};

const startAudioCapture = () => {
  talkAudioContext = new (window.AudioContext || window.webkitAudioContext)();
  const source = talkAudioContext.createMediaStreamSource(mediaStream);
  
  scriptProcessor = talkAudioContext.createScriptProcessor(2048, 1, 1);
  const inputSampleRate = talkAudioContext.sampleRate;

  let audioQueue = [];

  scriptProcessor.onaudioprocess = (event) => {
    if (!isIntercomActive.value || talkWs?.readyState !== WebSocket.OPEN) return;
    
    const inputData = event.inputBuffer.getChannelData(0);
    // 降采样并转码为 G.711A
    const alawBuffer = resampleAndEncodeToAlaw(inputData, inputSampleRate, 8000);
    
    // 放入缓冲队列
    for (let i = 0; i < alawBuffer.length; i++) {
      audioQueue.push(alawBuffer[i]);
    }

    // G.711A 每 20ms 发送 160 字节
    const packetSize = 160;
    while (audioQueue.length >= packetSize) {
      const packet = new Uint8Array(audioQueue.splice(0, packetSize));
      talkWs.send(packet.buffer);
    }
  };

  source.connect(scriptProcessor);
  scriptProcessor.connect(talkAudioContext.destination);
};

const stopIntercom = () => {
  isIntercomActive.value = false;
  connectingIntercom.value = false;

  if (scriptProcessor) {
    scriptProcessor.disconnect();
    scriptProcessor = null;
  }
  if (talkAudioContext) {
    try {
      talkAudioContext.close();
    } catch (e) {}
    talkAudioContext = null;
  }
  if (mediaStream) {
    mediaStream.getTracks().forEach(track => track.stop());
    mediaStream = null;
  }
  if (talkWs) {
    try {
      talkWs.close();
    } catch (e) {}
    talkWs = null;
  }
};

// FLV 播放逻辑
const startFLV = (flvUrl) => {
  closeStream(false);
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
      closeStream(true);
      ElMessage.error('视频流加载失败，请检查流媒体服务连接');
    }
  } else {
    ElMessage.error('当前浏览器不支持 FLV 播放');
  }
};

// WebRTC 协商握手逻辑
const startWebRTC = async (webrtcUrl) => {
  closeStream(false);
  loading.value = true;

  try {
    // 1. 创建 RTCPeerConnection 实例
    peerConnection = new RTCPeerConnection({
      iceServers: []
    });

    // 2. 添加仅接收音频和视频的 Transceiver
    peerConnection.addTransceiver('video', { direction: 'recvonly' });
    peerConnection.addTransceiver('audio', { direction: 'recvonly' });

    // 3. 监听轨道添加并进行音频处理
    peerConnection.ontrack = (event) => {
      console.log('收到媒体流轨道：', event.streams);

      // 确保音频上下文已创建
      if (!audioContext) {
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
        gainNode = audioContext.createGain();
        
        // 创建 3 个低通滤波器进行串联，使过滤斜率增加到 36dB/octave，以获得极强的啸叫/高频噪声抑制效果
        filterNodes = [];
        for (let i = 0; i < 3; i++) {
          const lp = audioContext.createBiquadFilter();
          lp.type = 'lowpass';
          lp.frequency.value = noiseFilterEnabled.value ? filterFrequency.value : 20000;
          filterNodes.push(lp);
        }
        
        highpassNode = audioContext.createBiquadFilter();
        highpassNode.type = 'highpass';
        highpassNode.frequency.value = noiseFilterEnabled.value ? 300 : 20; // 高通滤波，过滤 300Hz 以下低频杂音
        
        audioDest = audioContext.createMediaStreamDestination();
        // 初始增益值同步，尊重静音状态和音量设定
        gainNode.gain.value = isMuted.value ? 0 : (volumeVal.value / 100) * audioGain.value;
      }

      if (videoRef.value) {
        const remoteStream = event.streams[0];
        const videoTrack = remoteStream.getVideoTracks()[0];
        const audioTrack = remoteStream.getAudioTracks()[0];

        // 若有音频轨道，进行处理后混流
        let finalStream;
        if (audioTrack) {
          // 在 Chrome 中，必须有 HTMLMediaElement 播放流才能激活/拉取音频轨道数据到 AudioContext
          if (!dummyAudio) {
            dummyAudio = new Audio();
            dummyAudio.muted = true;
            dummyAudio.volume = 0;
          }
          
          // 仅在 srcObject 为空或者包含的轨道发生改变时才重新设定和播放，避免重复播放抛出 AbortError
          const currentTracks = dummyAudio.srcObject ? dummyAudio.srcObject.getAudioTracks() : [];
          if (!dummyAudio.srcObject || currentTracks.length === 0 || currentTracks[0].id !== audioTrack.id) {
            dummyAudio.srcObject = new MediaStream([audioTrack]);
            dummyAudio.play().catch(e => console.warn('Dummy audio autoplay failed', e));
          }

          // 重新构建音频源
          if (audioSource) {
            try {
              audioSource.disconnect();
            } catch (e) {}
          }
          audioSource = audioContext.createMediaStreamSource(dummyAudio.srcObject);

          // 重新构建音频处理链
          gainNode.disconnect();
          if (highpassNode) {
            highpassNode.disconnect();
          }
          if (filterNodes && filterNodes.length > 0) {
            filterNodes.forEach(node => node.disconnect());
          }
          
          audioSource.connect(gainNode);
          gainNode.connect(highpassNode);
          highpassNode.connect(filterNodes[0]);
          filterNodes[0].connect(filterNodes[1]);
          filterNodes[1].connect(filterNodes[2]);
          filterNodes[2].connect(audioDest);

          // 合并视频轨道与处理后音频轨道
          finalStream = new MediaStream([
            ...(videoTrack ? [videoTrack] : []),
            ...audioDest.stream.getAudioTracks()
          ]);
        } else {
          // 仅视频无音频，必须创建新 MediaStream 以防后续收到的音频轨道被浏览器直接播放
          finalStream = new MediaStream(videoTrack ? [videoTrack] : []);
        }
        videoRef.value.srcObject = finalStream;
        videoRef.value.muted = isMuted.value;
        videoRef.value.volume = volumeVal.value / 100;
        videoRef.value.play().then(() => {
          resumeAudioContext();
        }).catch(err => {
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
    // 确保在音频处理前已经根据当前设置更新过滤频率
    if (filterNodes && filterNodes.length > 0) {
      filterNodes.forEach(node => {
        node.frequency.value = noiseFilterEnabled.value ? filterFrequency.value : 20000;
      });
    }
    if (highpassNode) {
      highpassNode.frequency.value = noiseFilterEnabled.value ? 300 : 20;
    }
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
    closeStream(true);
    ElMessage.error('视频流加载失败，请检查流媒体服务连接');
  }
};

// 实时预览
const startLiveStream = async () => {
  if (!selectedDevice.value || !selectedChannel.value) return;
  
  const targetDevId = selectedDevice.value.deviceId;
  const targetChId = selectedChannel.value.channelId;
  loading.value = true;
  
  try {
    const res = await startPreviewApi(targetDevId, targetChId);
    
    // 竞态防抖检查：如果在等待网络响应期间用户切换了通道，则停止并忽略这个过时的流
    if (selectedDevice.value?.deviceId !== targetDevId || selectedChannel.value?.channelId !== targetChId) {
      stopPreviewApi(targetDevId, targetChId).catch(e => {});
      return;
    }

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
    if (selectedDevice.value?.deviceId === targetDevId && selectedChannel.value?.channelId === targetChId) {
      loading.value = false;
    }
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
          title: '设备告警触发',
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

// 监听消噪开关变化，动态更新音频节点参数
watch(noiseFilterEnabled, (val) => {
  if (audioContext) {
    if (filterNodes && filterNodes.length > 0) {
      filterNodes.forEach(node => {
        node.frequency.value = val ? filterFrequency.value : 20000;
      });
    }
    if (highpassNode) {
      highpassNode.frequency.value = val ? 300 : 20;
    }
  }
});

// 监听截止频率变化，动态调整低通滤波
watch(filterFrequency, (val) => {
  if (audioContext && filterNodes && filterNodes.length > 0 && noiseFilterEnabled.value) {
    filterNodes.forEach(node => {
      node.frequency.value = val;
    });
  }
});

// 监听增益增幅变化
watch(audioGain, (val) => {
  if (audioContext && gainNode) {
    gainNode.gain.value = val;
  }
});

// 恢复 AudioContext (解决浏览器自动播放限制)
const resumeAudioContext = () => {
  if (audioContext && audioContext.state === 'suspended') {
    audioContext.resume().then(() => {
      console.log('AudioContext 成功恢复');
    }).catch(err => {
      console.error('恢复 AudioContext 失败:', err);
    });
  }
};

// 同步视频音量到 gainNode 并同步更新 Vue ref 变量
const handleVolumeChange = () => {
  if (videoRef.value) {
    isMuted.value = videoRef.value.muted;
    volumeVal.value = Math.round(videoRef.value.volume * 100);
    if (gainNode) {
      if (isMuted.value) {
        gainNode.gain.value = 0;
      } else {
        gainNode.gain.value = videoRef.value.volume * audioGain.value;
      }
    }
  }
};

// 钩子
onMounted(() => {
  fetchDevices();
  initSse();
  window.addEventListener('click', resumeAudioContext);
  window.addEventListener('touchstart', resumeAudioContext);

  if (videoRef.value) {
    videoRef.value.addEventListener('volumechange', handleVolumeChange);
  }
});

onUnmounted(() => {
  closeStream();
  if (sseSource) {
    sseSource.close();
  }
  window.removeEventListener('click', resumeAudioContext);
  window.removeEventListener('touchstart', resumeAudioContext);

  if (videoRef.value) {
    videoRef.value.removeEventListener('volumechange', handleVolumeChange);
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

.audio-settings-section {
  display: flex;
  flex-direction: column;
  padding-bottom: 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.audio-settings-section h4 {
  margin: 0 0 15px 0;
  font-size: 14px;
  color: #cbd5e1;
  font-weight: 600;
}

.setting-item {
  margin-bottom: 15px;
}

.setting-item :deep(.el-slider) {
  height: 26px;
}

.setting-label {
  font-size: 13px;
  color: #94a3b8;
}

.slider-header {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 5px;
  color: #94a3b8;
}

.slider-val {
  color: #60a5fa;
  font-weight: 600;
}

.setting-hint {
  font-size: 11px;
  color: #64748b;
  margin-top: 2px;
  line-height: 1.4;
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

/* 自定义播放控制栏样式 (海康 CCTV 客户端风格) */
.custom-player-controls {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 44px;
  background: #141417;
  border-top: 2px solid #eab308; /* 黄色/金色顶边线 */
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  z-index: 10;
  box-shadow: 0 -2px 10px rgba(0, 0, 0, 0.5);
  user-select: none;
}

.controls-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.control-btn {
  background: none;
  border: 1px solid transparent;
  color: #a3a3a3;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 5px;
  border-radius: 2px;
  transition: all 0.2s ease;
}

.control-btn:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.06);
}

/* 语音对讲按钮高亮红框样式 */
.control-btn.talk-btn.active {
  color: #f87171 !important;
  border: 1.5px solid #ef4444 !important;
  background: rgba(239, 68, 68, 0.15) !important;
}

.control-btn.talk-btn.loading {
  color: #fbbf24 !important;
  background: rgba(251, 191, 36, 0.1) !important;
}

/* 音量控制组 */
.volume-control-container {
  display: flex;
  align-items: center;
  position: relative;
}

.volume-slider {
  width: 0;
  opacity: 0;
  height: 4px;
  -webkit-appearance: none;
  background: rgba(255, 255, 255, 0.2);
  outline: none;
  border-radius: 2px;
  transition: all 0.3s ease;
  cursor: pointer;
  margin-left: 0;
}

/* 悬浮展开音量滑块与数字 */
.volume-control-container:hover .volume-slider {
  width: 70px;
  opacity: 1;
  margin-left: 8px;
  margin-right: 8px;
}

.volume-text {
  font-size: 11px;
  color: #a3a3a3;
  width: 0;
  opacity: 0;
  overflow: hidden;
  transition: all 0.3s ease;
  white-space: nowrap;
  user-select: none;
  text-align: left;
}

.volume-control-container:hover .volume-text {
  width: 24px;
  opacity: 1;
}

/* Chrome/Safari 轨道滑块样式 */
.volume-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #eab308;
  cursor: pointer;
  transition: transform 0.1s;
}

.volume-slider::-webkit-slider-thumb:hover {
  transform: scale(1.2);
}

/* Firefox 轨道滑块样式 */
.volume-slider::-moz-range-thumb {
  width: 10px;
  height: 10px;
  border: none;
  border-radius: 50%;
  background: #eab308;
  cursor: pointer;
  transition: transform 0.1s;
}

.volume-slider::-moz-range-thumb:hover {
  transform: scale(1.2);
}

.controls-right {
  display: flex;
  align-items: center;
}

.live-badge {
  font-size: 10px;
  background: rgba(234, 179, 8, 0.15);
  color: #eab308;
  border: 1px solid rgba(234, 179, 8, 0.3);
  padding: 1px 5px;
  border-radius: 2px;
  font-weight: bold;
  letter-spacing: 0.5px;
}
</style>