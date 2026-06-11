package com.oldwei.isup.sdk;

import com.oldwei.isup.sdk.structure.*;
import com.oldwei.isup.util.CommonMethod;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import static com.oldwei.isup.sdk.service.constant.EHOME_ALARM_TYPE.*;

public class AlarmEventHandle {
    public static void processAlarmData(int dwAlarmType, Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen, Pointer pUrl, int dwUrlLen) {
        if (pUrl != Pointer.NULL) {
            dwAlarmType = EHOME_ISAPI_ALARM;
        }

        switch (dwAlarmType) {
            // Ehome鍩烘湰鎶ヨ
            case EHOME_ALARM: {
                processEhomeAlarm(dwAlarmType, pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // 鐑害鍥炬姤鍛?
            case EHOME_ALARM_HEATMAP_REPORT: {
                processEhomeAlarmHeatMapReport(pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // 鍥剧墖鎶撴媿鎶ュ憡
            case EHOME_ALARM_FACESNAP_REPORT: {
                processEhomeFaceSnapReport(pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // GPS淇℃伅涓婁紶
            case EHOME_ALARM_GPS: {
                processEhomeGps(pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // 鎶ヨ涓绘満CID鍛婅涓婁紶
            case EHOME_ALARM_CID_REPORT: {
                processEhomeCIDReport(pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // 鍥剧墖URL涓婃姤
            case EHOME_ALARM_NOTICE_PICURL: {
                processEhomeAlarmNoticPicUrl(pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // 寮傛澶辫触閫氱煡
            case EHOME_ALARM_NOTIFY_FAIL: {
                processEhomeNotifyFail(pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // 闂ㄧ浜嬩欢涓婃姤
            case EHOME_ALARM_ACS: {
                processEhomeAlarmAcs(pStru, dwStruLen);
                break;
            }
            // 鏃犵嚎缃戠粶淇℃伅涓婁紶
            case EHOME_ALARM_WIRELESS_INFO: {
                processAlarmWirelessInfo(pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // ISAPI鎶ヨ涓婁紶
            case EHOME_ISAPI_ALARM: {
                processEhomeIsapiAlarm(pStru, dwStruLen, pUrl, dwUrlLen);
                break;
            }
            // 杞﹁浇璁惧鐨勫娴佹暟鎹?
            case EHOME_ALARM_MPDCDATA: {
                processEhomeAlarmMpdcData(pStru, dwStruLen, pXml, dwXmlLen);
                break;
            }
            // 浜岀淮鐮佹姤璀︿笂浼?
            case EHOME_ALARM_QRCODE: {
                processEhomeAlarmQrcode(pXml, dwXmlLen);
                break;
            }
            // 浜鸿劯娴嬫俯鎶ヨ涓婁紶
            case EHOME_ALARM_FACETEMP: {
                processEhomeAlarmFaceTemp(pXml, dwXmlLen);
                break;
            }
            default: {
                System.out.println("unknown_Alarm_type: " + dwAlarmType);
            }
        }
    }

    /**
     * Ehome鍩烘湰鎶ヨ
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeAlarm(int alarmType, Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        NET_EHOME_ALARM_INFO ehomeAlarmInfo = new NET_EHOME_ALARM_INFO();
        ehomeAlarmInfo.write();
        Pointer pEhomeAlarmInfo = ehomeAlarmInfo.getPointer();
        pEhomeAlarmInfo.write(0, pStru.getByteArray(0, ehomeAlarmInfo.size()), 0, ehomeAlarmInfo.size());
        ehomeAlarmInfo.read();

        StringBuffer bf = new StringBuffer();
        bf.append("[ALARM]DeviceID:" + Native.toString(ehomeAlarmInfo.szDeviceID)
                + ",\nTime:" + Native.toString(ehomeAlarmInfo.szAlarmTime)
                + ",\nType:" + ehomeAlarmInfo.dwAlarmType
                + ",\nAction:" + ehomeAlarmInfo.dwAlarmAction
                + ",\nChannel:" + ehomeAlarmInfo.dwVideoChannel
                + ",\nAlarmIn:" + ehomeAlarmInfo.dwAlarmInChannel
                + ",\nDiskNo:" + ehomeAlarmInfo.dwDiskNumber);

        switch (alarmType) {
            case ALARM_TYPE_DEV_CHANGED_STATUS: {
                bf.append("\n[ALARM_TYPE_DEV_CHANGED_STATUS]byDeviceStatus:" + ehomeAlarmInfo.uStatusUnion.struDevStatusChanged.byDeviceStatus);
                break;
            }
            case ALARM_TYPE_CHAN_CHANGED_STATUS: {
                bf.append("\n[ALARM_TYPE_CHAN_CHANGED_STATUS]byChanStatus:" + ehomeAlarmInfo.uStatusUnion.struChanStatusChanged.byChanStatus
                        + ",wChanNO:" + ehomeAlarmInfo.uStatusUnion.struChanStatusChanged.wChanNO);
                break;
            }
            case ALARM_TYPE_HD_CHANGED_STATUS: {
                bf.append("\n[ALARM_TYPE_HD_CHANGED_STATUS]byHDStatus:" + ehomeAlarmInfo.uStatusUnion.struHdStatusChanged.byHDStatus
                        + ",wHDNo:" + ehomeAlarmInfo.uStatusUnion.struHdStatusChanged.wHDNo
                        + ",dwVolume:" + ehomeAlarmInfo.uStatusUnion.struHdStatusChanged.dwVolume);
                break;
            }
            case ALARM_TYPE_DEV_TIMING_STATUS: {
                bf.append("\n[ALARM_TYPE_DEV_TIMING_STATUS]byCPUUsage:%d" + ehomeAlarmInfo.uStatusUnion.struDevTimeStatus.byCPUUsage +
                        ",byMainFrameTemp:%d" + ehomeAlarmInfo.uStatusUnion.struDevTimeStatus.byMainFrameTemp +
                        ",byBackPanelTemp:%d" + ehomeAlarmInfo.uStatusUnion.struDevTimeStatus.byBackPanelTemp +
                        ",dwMemoryTotal:%d" + ehomeAlarmInfo.uStatusUnion.struDevTimeStatus.dwMemoryTotal +
                        ",dwMemoryUsage:%d" + ehomeAlarmInfo.uStatusUnion.struDevTimeStatus.dwMemoryUsage);
                break;
            }
            case ALARM_TYPE_CHAN_TIMING_STATUS: {
                bf.append("\n[ALARM_TYPE_CHAN_TIMING_STATUS]byLinkNum:%d" + ehomeAlarmInfo.uStatusUnion.struChanTimeStatus.byLinkNum +
                        ",wChanNO:%d" + ehomeAlarmInfo.uStatusUnion.struChanTimeStatus.wChanNO +
                        ",dwBitRate:%d" + ehomeAlarmInfo.uStatusUnion.struChanTimeStatus.dwBitRate);
                break;
            }
            case ALARM_TYPE_HD_TIMING_STATUS: {
                bf.append("\n[ALARM_TYPE_HD_TIMING_STATUS]wHDNo:%d" + ehomeAlarmInfo.uStatusUnion.struHdTimeStatus.wHDNo +
                        ",dwHDFreeSpace:%d" + ehomeAlarmInfo.uStatusUnion.struHdTimeStatus.dwHDFreeSpace);
                break;
            }
            default: {
                break;
            }
        }

        handleAlarmInfo(EHOME_ALARM, bf.toString());
    }

    /**
     * 鐑害鍥炬姤鍛?
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeAlarmHeatMapReport(Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        if (pStru == Pointer.NULL) {
            return;
        }
        NET_EHOME_HEATMAP_REPORT struHeatMapReport = new NET_EHOME_HEATMAP_REPORT();
        struHeatMapReport.write();
        Pointer pStruHeatMapReport = struHeatMapReport.getPointer();
        pStruHeatMapReport.write(0, pStru.getByteArray(0, struHeatMapReport.size()), 0, struHeatMapReport.size());
        struHeatMapReport.read();

        String info = "[HEATMAPREPORT]DeviceID: " + Native.toString(struHeatMapReport.byDeviceID) +
                ",\nChannel: " + struHeatMapReport.dwVideoChannel +
                ",\nStart: " + Native.toString(struHeatMapReport.byStartTime) +
                ",\nStop: " + Native.toString(struHeatMapReport.byStopTime) +
                ",\nHeatMapValue: " + struHeatMapReport.struHeatmapValue.dwMaxHeatMapValue
                + "  " + struHeatMapReport.struHeatmapValue.dwMinHeatMapValue
                + "  " + struHeatMapReport.struHeatmapValue.dwTimeHeatMapValue +
                ",\nSize: " + struHeatMapReport.struPixelArraySize.dwLineValue
                + "  " + struHeatMapReport.struPixelArraySize.dwColumnValue;

        handleAlarmInfo(EHOME_ALARM_HEATMAP_REPORT, info);
    }


    /**
     * 鍥剧墖鎶撴媿鎶ュ憡
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeFaceSnapReport(Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        if (pStru == Pointer.NULL) {
            return;
        }
        NET_EHOME_FACESNAP_REPORT struFaceSnapReport = new NET_EHOME_FACESNAP_REPORT();
        struFaceSnapReport.write();
        Pointer pStruFaceSnapReport = struFaceSnapReport.getPointer();
        pStruFaceSnapReport.write(0, pStru.getByteArray(0, struFaceSnapReport.size()), 0, struFaceSnapReport.size());
        struFaceSnapReport.read();

        StringBuffer bf = new StringBuffer();

        bf.append("[FACESNAPREPORT]DeviceID: " + Native.toString(struFaceSnapReport.byDeviceID) +
                ",\nChannel:" + struFaceSnapReport.dwVideoChannel +
                ",\nTime:" + Native.toString(struFaceSnapReport.byAlarmTime) +
                ",\nPicID:" + struFaceSnapReport.dwFacePicID +
                ",\nScore:" + struFaceSnapReport.dwFaceScore +
                ",\nTargetID:" + struFaceSnapReport.dwTargetID +
                ",\nTarget Zone[" +
                " " + struFaceSnapReport.struTarketZone.dwX +
                " " + struFaceSnapReport.struTarketZone.dwY +
                " " + struFaceSnapReport.struTarketZone.dwWidth +
                " " + struFaceSnapReport.struTarketZone.dwHeight +
                "]" +
                ",\nFacePicZone[" +
                " " + struFaceSnapReport.struFacePicZone.dwX +
                " " + struFaceSnapReport.struFacePicZone.dwY +
                " " + struFaceSnapReport.struFacePicZone.dwWidth +
                " " + struFaceSnapReport.struFacePicZone.dwHeight +
                "]" +
                ",\nHumanFeature:[" +
                " " + struFaceSnapReport.struHumanFeature.byAgeGroup +
                " " + struFaceSnapReport.struHumanFeature.bySex +
                " " + struFaceSnapReport.struHumanFeature.byEyeGlass +
                " " + struFaceSnapReport.struHumanFeature.byMask +
                " " +
                "]" +
                ",\nDuration:" + struFaceSnapReport.dwStayDuration +
                ",\nFacePicLen:" + struFaceSnapReport.dwFacePicLen +
                ",\nBackGroundPicLen:" + struFaceSnapReport.dwBackgroudPicLen
        );

        handleAlarmInfo(EHOME_ALARM_FACESNAP_REPORT, bf.toString());
    }

    /**
     * GPS淇℃伅涓婁紶
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeGps(Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        if (pStru == Pointer.NULL) {
            return;
        }
        NET_EHOME_GPS_INFO struGps = new NET_EHOME_GPS_INFO();
        struGps.write();
        Pointer pStr = struGps.getPointer();
        pStr.write(0, pStru.getByteArray(0, struGps.size()), 0, struGps.size());
        struGps.read();

        StringBuffer bf = new StringBuffer();
        bf.append("[GPS]DeviceID:" + Native.toString(struGps.byDeviceID) +
                ",\nSampleTime:" + Native.toString(struGps.bySampleTime) +
                ",\nDivision:[" +
                "" + struGps.byDivision[0] +
                " " + struGps.byDivision[1] +
                "]" +
                ",\nSatelites:" + struGps.bySatelites +
                ",\nPrecision:" + struGps.byPrecision +
                ",\nLongitude:" + struGps.dwLongitude +
                ",\nLatitude:" + struGps.dwLatitude +
                ",\nDirection:" + struGps.dwDirection +
                ",\nSpeed:" + struGps.dwSpeed +
                ",\nHeight:" + struGps.dwHeight);
        handleAlarmInfo(EHOME_ALARM_GPS, bf.toString());
    }

    /**
     * 鎶ヨ涓绘満CID鍛婅涓婁紶
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeCIDReport(Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        if (pStru == Pointer.NULL) {
            return;
        }
        NET_EHOME_CID_INFO strCidInfo = new NET_EHOME_CID_INFO();
        NET_EHOME_CID_INFO_INTERNAL_EX strCidInfoEx = new NET_EHOME_CID_INFO_INTERNAL_EX();
        NET_EHOME_CID_INFO_PICTUREINFO_EX strPicInfoEx = new NET_EHOME_CID_INFO_PICTUREINFO_EX();

        strCidInfo.write();
        Pointer pStrCidInfo = strCidInfo.getPointer();
        pStrCidInfo.write(0, pStru.getByteArray(0, strCidInfo.size()), 0, strCidInfo.size());
        strCidInfo.read();

        strCidInfoEx.write();
        Pointer pStrCidInfoEx = strCidInfoEx.getPointer();
        pStrCidInfoEx.write(0, strCidInfo.pCidInfoEx.getByteArray(0, strCidInfoEx.size()), 0, strCidInfoEx.size());
        strCidInfoEx.read();

        strPicInfoEx.write();
        Pointer pStrPicInfoEx = strPicInfoEx.getPointer();
        pStrPicInfoEx.write(0, strCidInfo.pPicInfoEx.getByteArray(0, strPicInfoEx.size()), 0, strPicInfoEx.size());
        strPicInfoEx.read();

        String cDescribe = Native.toString(strCidInfo.byCIDDescribe);
        String uuid = null;
        StringBuffer bf = new StringBuffer();
        bf.append("\ncDescribe: " + cDescribe);

        // 鏈夋嫇灞曞瓧娈靛垯澶勭悊鎷撳睍瀛楁淇℃伅
        if (strCidInfo.byExtend == 1) {
            cDescribe = Native.toString(strCidInfoEx.byCIDDescribeEx);
            uuid = Native.toString(strCidInfoEx.byUUID);
            bf.append("\n[CID_EX]uuid[" + Native.toString(strCidInfoEx.byUUID) + "]" +
                    ",\nrecheck[" + strCidInfoEx.byRecheck + "]" +
                    ",\nRecheck URL[" + Native.toString(strCidInfoEx.byVideoURL) + "]" +
                    ",\nvideoType[" + Native.toString(strCidInfoEx.byVideoType) + "]");
            for (int i = 0; i < MAX_PICTURE_NUM; i++) {
                if ((strCidInfoEx.byRecheck == 1) && (strPicInfoEx.byPictureURL[i][0]) != '\0') {
                    bf.append("\n[CID_EX]uuid[" + Native.toString(strCidInfoEx.byUUID) + "]" +
                            ",\nrecheck[" + strCidInfoEx.byRecheck + "]" +
                            ",\nPicURL[" + Native.toString(strPicInfoEx.byPictureURL[i]) + "]");
                }
            }

            StringBuilder LinkedSubSystem = new StringBuilder();
            for (int i = 0; i < MAX_SUBSYSTEM_LEN; i++) {
                if (strCidInfoEx.byLinkageSubSystem[i] > 0)//鍏宠仈瀛愮郴缁熸渶灏忓€间负1
                {
                    LinkedSubSystem.append(strCidInfoEx.byLinkageSubSystem[i]);
                }
            }
            bf.append("\n[CID_EX] All Linked SubSystem: " + LinkedSubSystem);
        }

        bf.append("\n[CID]uuid[%s]" + uuid +
                ",\nDeviceID:%s" + Native.toString(strCidInfo.byDeviceID) +
                ",\nCID code:%d" + strCidInfo.dwCIDCode +
                ",\nCID type:%d" + strCidInfo.dwCIDType +
                ",\nSubsys No:%d" + strCidInfo.dwSubSysNo +
                ",\nDescribe:%s" + cDescribe +
                ",\nTriggerTime:%s" + Native.toString(strCidInfo.byTriggerTime) +
                ",\nUploadTime:%s" + Native.toString(strCidInfo.byUploadTime) +
                ",\nCID param[" +
                "  " + strCidInfo.struCIDParam.dwUserType +
                "  " + strCidInfo.struCIDParam.lUserNo +
                "  " + strCidInfo.struCIDParam.lZoneNo +
                "  " + strCidInfo.struCIDParam.lKeyboardNo +
                "  " + strCidInfo.struCIDParam.lVideoChanNo +
                "  " + strCidInfo.struCIDParam.lDiskNo +
                "  " + strCidInfo.struCIDParam.lModuleAddr +
                //UTF-8杞珿BK
                "  " + CommonMethod.UTF8toGBKStr(strCidInfo.struCIDParam.byUserName) +
                "]");

        handleAlarmInfo(EHOME_ALARM_CID_REPORT, bf.toString());
    }

    /**
     * 鍥剧墖URL涓婃姤
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeAlarmNoticPicUrl(Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        if (pStru == Pointer.NULL) {
            return;
        }

        NET_EHOME_NOTICE_PICURL pStruNoticePicUrl = new NET_EHOME_NOTICE_PICURL();
        pStruNoticePicUrl.write();
        Pointer pointer = pStruNoticePicUrl.getPointer();
        pointer.write(0, pStru.getByteArray(0, pStruNoticePicUrl.size()), 0, pStruNoticePicUrl.size());
        pStruNoticePicUrl.read();

        StringBuffer bf = new StringBuffer();
        bf.append("[NOTICEPICURL]DeviceID: " + Native.toString(pStruNoticePicUrl.byDeviceID) +
                ",\nPicType: " + pStruNoticePicUrl.wPicType +
                ",\nAlarmType: " + pStruNoticePicUrl.wAlarmType +
                ",\nAlarmChan: " + pStruNoticePicUrl.dwAlarmChan +
                ",\nAlarmTime: " + Native.toString(pStruNoticePicUrl.byAlarmTime) +
                ",\nCaptureChan: " + pStruNoticePicUrl.dwCaptureChan +
                ",\nPicTime: " + Native.toString(pStruNoticePicUrl.byPicTime) +
                ",\nURL: " + Native.toString(pStruNoticePicUrl.byPicUrl) +
                ",\nManualSeq: " + pStruNoticePicUrl.dwManualSnapSeq);

        handleAlarmInfo(EHOME_ALARM_NOTICE_PICURL, bf.toString());
    }

    /**
     * 寮傛澶辫触閫氱煡
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeNotifyFail(Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        if (pStru == Pointer.NULL) {
            return;
        }
        NET_EHOME_NOTIFY_FAIL_INFO strucInfo = new NET_EHOME_NOTIFY_FAIL_INFO();
        strucInfo.write();
        Pointer pointer = strucInfo.getPointer();
        pointer.write(0, pStru.getByteArray(0, strucInfo.size()), 0, strucInfo.size());
        strucInfo.read();

        StringBuffer sb = new StringBuffer();
        sb.append("[NOTIFYFAIL]DeviceID: " + Native.toString(strucInfo.byDeviceID) +
                ",\nFailedCommand: " + strucInfo.wFailedCommand +
                ",\nPicType: " + strucInfo.wPicType +
                ",\nManualSeq: " + strucInfo.dwManualSnapSeq);

        handleAlarmInfo(EHOME_ALARM_NOTIFY_FAIL, sb.toString());
    }

    /**
     * 闂ㄧ浜嬩欢涓婃姤
     *
     * @param pStru
     * @param dwStruLen
     */
    public static void processEhomeAlarmAcs(Pointer pStru, int dwStruLen) {
        if (pStru == Pointer.NULL || dwStruLen == 0) {
            return;
        }

        BYTE_ARRAY strXMLData = new BYTE_ARRAY(dwStruLen);
        strXMLData.write();
        Pointer pPlateInfo = strXMLData.getPointer();
        pPlateInfo.write(0, pStru.getByteArray(0, strXMLData.size()), 0, strXMLData.size());
        strXMLData.read();
        String strXML = new String(strXMLData.byValue).trim();

        handleAlarmInfo(EHOME_ALARM_ACS, strXML);
    }

    /**
     * 鏃犵嚎缃戠粶淇℃伅涓婁紶
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processAlarmWirelessInfo(Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        NET_EHOME_ALARMWIRELESSINFO strucInfo = new NET_EHOME_ALARMWIRELESSINFO();
        strucInfo.write();
        Pointer pointer = strucInfo.getPointer();
        pointer.write(0, pStru.getByteArray(0, strucInfo.size()), 0, strucInfo.size());
        strucInfo.read();

        StringBuffer sb = new StringBuffer();
        sb.append("[Wireless]DeviceID: " + Native.toString(strucInfo.byDeviceID) +
                ",\nDataTraffic: " + ((float) strucInfo.dwDataTraffic) / 100 +
                ",\nSignalIntensity:" + strucInfo.bySignalIntensity);

        handleAlarmInfo(EHOME_ALARM_WIRELESS_INFO, sb.toString());
    }

    /**
     * ISAPI鎶ヨ涓婁紶
     *
     * @param pStru
     * @param dwStruLen
     * @param pUrl
     * @param dwUrlLen
     */
    public static void processEhomeIsapiAlarm(Pointer pStru, int dwStruLen, Pointer pUrl, int dwUrlLen) {
        if (pUrl != Pointer.NULL && dwUrlLen > 0) {
            // ISUP4.0浜嬩欢涓婃姤锛岄粯璁ゆ姤璀︿笌鍥剧墖鏁版嵁涓嶅垎绂伙紝姝ゆ椂杩斿洖鐨剈rl涓嶄负绌猴紝杩欑鎯呭喌澧炲姞涓媎emo鎵撳嵃
            // 鍒嗙鍚庣殑鏁版嵁锛寀rl瀛楁涓嶄负绌?
            System.out.println("ISAPI鎶ヨ涓婁紶, ISAPI Alarm");
            return;
        }

        if (pStru == Pointer.NULL) {
            return;
        }

        NET_EHOME_ALARM_ISAPI_INFO strISAPIAlarm = new NET_EHOME_ALARM_ISAPI_INFO();
        strISAPIAlarm.write();
        Pointer pISAPIAlarm = strISAPIAlarm.getPointer();
        pISAPIAlarm.write(0, pStru.getByteArray(0, strISAPIAlarm.size()), 0, strISAPIAlarm.size());
        strISAPIAlarm.read();

        if (strISAPIAlarm.pAlarmData != Pointer.NULL) {
            String alarmData = null;
            // 鍒ゆ柇鎶ヨ鏁版嵁鐨勬牸寮?
            if (strISAPIAlarm.byDataType != 0) { // 1: xml鏍煎紡鏁版嵁 2锛歫son鏍煎紡鏁版嵁
                BYTE_ARRAY m_strISAPIData = new BYTE_ARRAY(strISAPIAlarm.dwAlarmDataLen);
                m_strISAPIData.write();
                Pointer pPlateInfo = m_strISAPIData.getPointer();
                pPlateInfo.write(0, strISAPIAlarm.pAlarmData.getByteArray(0, m_strISAPIData.size()), 0, m_strISAPIData.size());
                m_strISAPIData.read();

                alarmData = new String(m_strISAPIData.byValue).trim();

                handleAlarmInfo(EHOME_ISAPI_ALARM, alarmData);
            }
        }
    }

    /**
     * 杞﹁浇璁惧鐨勫娴佹暟鎹?
     *
     * @param pStru
     * @param dwStruLen
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeAlarmMpdcData(Pointer pStru, int dwStruLen, Pointer pXml, int dwXmlLen) {
        NET_EHOME_ALARM_MPDCDATA structure = new NET_EHOME_ALARM_MPDCDATA();
        structure.write();
        Pointer pointer = structure.getPointer();
        pointer.write(0, pStru.getByteArray(0, structure.size()), 0, structure.size());
        structure.read();

        StringBuffer sb = new StringBuffer();
        sb.append("[MPDCData]DeviceID:" + Native.toString(structure.byDeviceID) +
                ",\nSampleTime: " + Native.toString(structure.bySampleTime) +
                ",\nRetranseFlag: " + structure.byRetranseFlag +
                ",\nCount: " + structure.struMPData.dwCount
        );
        handleAlarmInfo(EHOME_ALARM_MPDCDATA, sb.toString());
    }

    /**
     * 浜岀淮鐮佹姤璀︿笂浼?
     *
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeAlarmQrcode(Pointer pXml, int dwXmlLen) {
        if (pXml == Pointer.NULL) {
            return;
        }
        BYTE_ARRAY strXMLData = new BYTE_ARRAY(dwXmlLen);
        strXMLData.write();
        Pointer pPlateInfo = strXMLData.getPointer();
        pPlateInfo.write(0, pXml.getByteArray(0, strXMLData.size()), 0, strXMLData.size());
        strXMLData.read();

        String strXML = new String(strXMLData.byValue).trim();
        handleAlarmInfo(EHOME_ALARM_QRCODE, strXML);
    }

    /**
     * 浜鸿劯娴嬫俯鎶ヨ涓婁紶
     *
     * @param pXml
     * @param dwXmlLen
     */
    public static void processEhomeAlarmFaceTemp(Pointer pXml, int dwXmlLen) {
        if (pXml == Pointer.NULL || dwXmlLen == 0) {
            return;
        }

        BYTE_ARRAY strXMLData = new BYTE_ARRAY(dwXmlLen);
        strXMLData.write();
        Pointer pPlateInfo = strXMLData.getPointer();
        pPlateInfo.write(0, pXml.getByteArray(0, strXMLData.size()), 0, strXMLData.size());
        strXMLData.read();

        String strXML = new String(strXMLData.byValue).trim();
        handleAlarmInfo(EHOME_ALARM_FACETEMP, strXML);
    }

    /**
     * 澶勭悊鍛婅淇℃伅锛堣緭鍑哄埌鏂囦欢鎴栬€呮槸杈撳嚭鍒版帶鍒跺彴锛?
     *
     * @param alarmType
     * @param info
     */
        private static void handleAlarmInfo(int alarmType, String info) {
        // 输出事件信息到文件中
        CommonMethod.outputToFile("dwAlarmType_" + alarmType, ".txt", info);
        // 发送实时 SSE 报警到前端
        com.oldwei.isup.controller.AlarmController.sendAlarm("dwAlarmType_" + alarmType, info);
    }
}
