<template>
  <div class="app-container home">

    <el-row :gutter="20">
      <el-col :sm="24" :lg="24" style="padding-left: 20px">
        <h2>海康 ISUP 监控平台</h2>
        <p>
          本项目基于 RuoYi-Vue 框架开发，集成了海康威视 ISUP (EHome) 协议，实现了海康摄像机/设备的注册接入、实时状态监控、报警事件接收、实时视频预览（支持 WebRTC 协议）、以及双向语音对讲（支持前端消噪与增益调节）等功能，为海康 ISUP 设备提供了一套开箱即用的智能化监控管理方案。
        </p>
        <p>
          <b>当前版本:</b> <span>v{{ version }}</span>
        </p>
        <p>
          <el-tag type="success">免费开源</el-tag>
          <el-tag type="warning" style="margin-left: 8px;">支持个人与企业免费使用</el-tag>
        </p>
        <p>
          <el-button
            type="primary"
            icon="Cloudy"
            plain
            @click="goTarget('https://gitee.com/super_888/ruoyi-hik-isup')"
            >访问 Gitee</el-button
          >
          <el-button
            icon="HomeFilled"
            plain
            @click="goTarget('https://github.com/super8p/ruoyi-hik-isup')"
            >访问 GitHub</el-button
          >
          <el-button
            type="danger"
            icon="ColdDrink"
            plain
            @click="donationVisible = true"
            >打赏支持</el-button
          >
        </p>
      </el-col>
    </el-row>
    <el-divider />
    <el-row :gutter="20">
      <el-col :xs="24" :sm="24" :md="24" :lg="24">
        <el-card class="update-log">
          <template v-slot:header>
            <div class="clearfix">
              <span>作者与版权信息</span>
            </div>
          </template>
          <div class="body">
            <p style="margin-bottom: 12px; display: flex; align-items: center;">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right: 8px; color: #409eff;">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
              </svg>
              <span><b>作者 QQ：</b>27988448</span>
            </p>
            <p style="margin-bottom: 12px; display: flex; align-items: center;">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right: 8px; color: #eab308;">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
              </svg>
              <span><b>交流 QQ群：</b>859712927</span>
            </p>
            <p style="display: flex; align-items: center;">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right: 8px; color: #67c23a;">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
              <span><b>授权许可：</b><span style="color: #67c23a; font-weight: bold;">开源免费（支持个人与企业免费商用）</span></span>
            </p>
          </div>
        </el-card>
      </el-col>
      

    </el-row>

    <!-- 打赏弹窗 -->
    <el-dialog title="打赏支持" v-model="donationVisible" width="550px" append-to-body>
      <div style="text-align: center; padding: 10px 0;">
        <p style="margin-bottom: 24px; font-size: 15px; color: #5a5e66; line-height: 1.6;">
          如果您觉得这个项目对您有所帮助，欢迎打赏一杯咖啡支持作者！<br/>
          作者利用业余时间持续维护项目，您的支持是动力源泉。
        </p>
        <div style="display: flex; justify-content: space-around; align-items: center; gap: 20px;">
          <div style="text-align: center;">
            <img :src="wechatPayImg" style="width: 200px; height: 200px; object-fit: cover; border-radius: 8px; border: 1px solid #eaeaea; box-shadow: 0 4px 12px rgba(0,0,0,0.08);" />
            <div style="margin-top: 12px; font-weight: bold; color: #07c160; display: flex; align-items: center; justify-content: center; gap: 6px;">
              <span>微信扫码打赏</span>
            </div>
          </div>
          <div style="text-align: center;">
            <img :src="alipayImg" style="width: 200px; height: 200px; object-fit: cover; border-radius: 8px; border: 1px solid #eaeaea; box-shadow: 0 4px 12px rgba(0,0,0,0.08);" />
            <div style="margin-top: 12px; font-weight: bold; color: #1677ff; display: flex; align-items: center; justify-content: center; gap: 6px;">
              <span>支付宝扫码打赏</span>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup name="Index">
import wechatPayImg from '@/assets/logo/pay_wechat.jpg'
import alipayImg from '@/assets/logo/pay_alipay.jpg'

const version = ref('3.8.7')
const donationVisible = ref(false)

function goTarget(url) {
  window.open(url, '__blank')
}
</script>

<style scoped lang="scss">
.home {
  blockquote {
    padding: 10px 20px;
    margin: 0 0 20px;
    font-size: 17.5px;
    border-left: 5px solid #eee;
  }
  hr {
    margin-top: 20px;
    margin-bottom: 20px;
    border: 0;
    border-top: 1px solid #eee;
  }
  .col-item {
    margin-bottom: 20px;
  }

  ul {
    padding: 0;
    margin: 0;
  }

  font-family: "open sans", "Helvetica Neue", Helvetica, Arial, sans-serif;
  font-size: 13px;
  color: #676a6c;
  overflow-x: hidden;

  ul {
    list-style-type: none;
  }

  h4 {
    margin-top: 0px;
  }

  h2 {
    margin-top: 10px;
    font-size: 26px;
    font-weight: 100;
  }

  p {
    margin-top: 10px;

    b {
      font-weight: 700;
    }
  }

  .update-log {
    ol {
      display: block;
      list-style-type: decimal;
      margin-block-start: 1em;
      margin-block-end: 1em;
      margin-inline-start: 0;
      margin-inline-end: 0;
      padding-inline-start: 40px;
    }
  }
}
</style>

