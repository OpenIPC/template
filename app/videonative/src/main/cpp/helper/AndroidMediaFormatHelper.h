
#ifndef FPVUE_ANDROIDMEDIAFORMATHELPER_H
#define FPVUE_ANDROIDMEDIAFORMATHELPER_H

#include <media/NdkMediaFormat.h>
#include "../NALU/KeyFrameFinder.hpp"

// Decoder tuning that trades pipeline depth for latency. Unknown keys are ignored by
// MediaCodec, so writing all of them is safe on every device / Android version.
static void writeAndroidPerformanceParams(AMediaFormat* format)
{
    // AMEDIAFORMAT_KEY_LOW_LATENCY (API 30+). Tells the decoder to output a frame as soon
    // as it is decoded instead of keeping a reorder/output queue. For a live stream that
    // never uses B-frames the queue only adds latency.
    AMediaFormat_setInt32(format, "low-latency", 1);
    // Vendor equivalents for SoCs whose codec does not pick up the AOSP key. Qualcomm is
    // the relevant one for most phones and for the Snapdragon XR2 headsets.
    AMediaFormat_setInt32(format, "vendor.low-latency.enable", 1);
    AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);
    AMediaFormat_setInt32(format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
    AMediaFormat_setInt32(format, "vendor.rtc-ext-dec-low-latency.enable", 1);
    // MediaCodec knows two priorities: 0 - realtime, 1 - best effort. Lower is higher.
    AMediaFormat_setInt32(format, "priority", 0);
}

static void h264_configureAMediaFormat(KeyFrameFinder& kff, AMediaFormat* format)
{
    const auto sps     = kff.getCSD0();
    const auto pps     = kff.getCSD1();
    const auto videoWH = sps.getVideoWidthHeightSPS();
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, videoWH[0]);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, videoWH[1]);
    AMediaFormat_setBuffer(format, "csd-0", sps.getData(), (size_t) sps.getSize());
    AMediaFormat_setBuffer(format, "csd-1", pps.getData(), (size_t) pps.getSize());
    MLOGD << "Video WH:" << videoWH[0] << " H:" << videoWH[1];
    // AMediaFormat_setInt32(format,AMEDIAFORMAT_KEY_BIT_RATE,5*1024*1024);
    // AMediaFormat_setInt32(format,AMEDIAFORMAT_KEY_FRAME_RATE,60);
    // AVCProfileBaseline==1
    // AMediaFormat_setInt32(decoder.format,AMEDIAFORMAT_KEY_PROFILE,1);
}

static void h265_configureAMediaFormat(KeyFrameFinder& kff, AMediaFormat* format)
{
    std::vector<uint8_t> buff = {};
    const auto           sps  = kff.getCSD0();
    const auto           pps  = kff.getCSD1();
    const auto           vps  = kff.getVPS();
    buff.reserve(sps.getSize() + pps.getSize() + vps.getSize());
    KeyFrameFinder::appendNaluData(buff, vps);
    KeyFrameFinder::appendNaluData(buff, sps);
    KeyFrameFinder::appendNaluData(buff, pps);
    const auto videoWH = sps.getVideoWidthHeightSPS();
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, videoWH[0]);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, videoWH[1]);
    AMediaFormat_setBuffer(format, "csd-0", buff.data(), buff.size());
    MLOGD << "Video WH:" << videoWH[0] << " H:" << videoWH[1];
}

#endif  // FPVUE_ANDROIDMEDIAFORMATHELPER_H
