#include <jni.h>
#include <arm_neon.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

inline int source_index(int ox, int oy, int width, int height, int degrees) {
    int sx, sy;
    switch (degrees) {
        case 0:   sx = ox;              sy = oy;                  break;
        case 90:  sx = oy;              sy = height - 1 - ox;    break;
        case 180: sx = width - 1 - ox;  sy = height - 1 - oy;    break;
        default:  sx = width - 1 - oy;  sy = ox;                  break;
    }
    return sy * width + sx;
}

// floor(v / 4) for any sign, matching Kotlin Math.floorDiv(v, 4) and an arithmetic >> 2.
inline int floor_div4(int v) {
    return v >= 0 ? v / 4 : -((-v + 3) / 4);
}

inline int floor_div9(int v) {
    return v >= 0 ? v / 9 : -((-v + 8) / 9);
}

inline float encoded(const jint* plane, int p, double gain, const jint* lut, int lut_max) {
    int index = static_cast<int>(static_cast<double>(plane[p]) * gain);
    index = std::max(0, std::min(index, lut_max));
    return static_cast<float>(lut[index]);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_activeperception_NativeTensorPreprocessor_clearTensor(
        JNIEnv* env, jobject, jobject destination, jint float_offset, jint float_count,
        jfloat value) {
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(destination));
    if (!dst) return;
    dst += float_offset;
    const float32x4_t fill = vdupq_n_f32(value);
    int i = 0;
    for (; i + 16 <= float_count; i += 16) {
        vst1q_f32(dst + i, fill); vst1q_f32(dst + i + 4, fill);
        vst1q_f32(dst + i + 8, fill); vst1q_f32(dst + i + 12, fill);
    }
    for (; i + 4 <= float_count; i += 4) vst1q_f32(dst + i, fill);
    for (; i < float_count; ++i) dst[i] = value;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_activeperception_NativeTensorPreprocessor_fillOriginalSrgbStripe(
        JNIEnv* env, jobject,
        jintArray r_array, jintArray g_array, jintArray b_array,
        jint width, jint height, jint orientation, jdouble gain,
        jintArray lut_array, jobject destination, jint base_float,
        jint image_size, jint output_y_start, jint output_y_end) {
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(destination));
    if (!dst) return JNI_FALSE;
    auto* r = static_cast<jint*>(env->GetPrimitiveArrayCritical(r_array, nullptr));
    auto* g = static_cast<jint*>(env->GetPrimitiveArrayCritical(g_array, nullptr));
    auto* b = static_cast<jint*>(env->GetPrimitiveArrayCritical(b_array, nullptr));
    auto* lut = static_cast<jint*>(env->GetPrimitiveArrayCritical(lut_array, nullptr));
    if (!r || !g || !b || !lut) {
        if (r) env->ReleasePrimitiveArrayCritical(r_array, r, JNI_ABORT);
        if (g) env->ReleasePrimitiveArrayCritical(g_array, g, JNI_ABORT);
        if (b) env->ReleasePrimitiveArrayCritical(b_array, b, JNI_ABORT);
        if (lut) env->ReleasePrimitiveArrayCritical(lut_array, lut, JNI_ABORT);
        return JNI_FALSE;
    }

    const int degrees = ((orientation % 360) + 360) % 360;
    const int oriented_w = (degrees == 90 || degrees == 270) ? height : width;
    const int oriented_h = (degrees == 90 || degrees == 270) ? width : height;
    const float scale = std::min(static_cast<float>(image_size) / oriented_w,
                                 static_cast<float>(image_size) / oriented_h);
    const int nw = std::lround(oriented_w * scale);
    const int nh = std::lround(oriented_h * scale);
    const int pad_x = (image_size - nw) / 2;
    const int pad_y = (image_size - nh) / 2;
    const int lut_max = env->GetArrayLength(lut_array) - 1;
    dst += base_float;

    std::vector<int> x0(nw), x1(nw);
    std::vector<float> fx(nw);
    for (int dx = 0; dx < nw; ++dx) {
        const double sx = std::clamp((dx + 0.5) * oriented_w / nw - 0.5,
                                     0.0, static_cast<double>(oriented_w - 1));
        x0[dx] = static_cast<int>(std::floor(sx));
        x1[dx] = std::min(x0[dx] + 1, oriented_w - 1);
        fx[dx] = static_cast<float>(sx - x0[dx]);
    }

    const int begin = std::max(0, output_y_start);
    const int end = std::min(nh, output_y_end);
    alignas(16) float q00[3][4], q10[3][4], q01[3][4], q11[3][4];
    const jint* planes[3] = {r, g, b};
    const float32x4_t inv255 = vdupq_n_f32(1.0f / 255.0f);
    for (int dy = begin; dy < end; ++dy) {
        const double sy = std::clamp((dy + 0.5) * oriented_h / nh - 0.5,
                                     0.0, static_cast<double>(oriented_h - 1));
        const int y0 = static_cast<int>(std::floor(sy));
        const int y1 = std::min(y0 + 1, oriented_h - 1);
        const float fy_scalar = static_cast<float>(sy - y0);
        const float32x4_t fyv = vdupq_n_f32(fy_scalar);
        int dx = 0;
        for (; dx + 4 <= nw; dx += 4) {
            for (int lane = 0; lane < 4; ++lane) {
                const int x = dx + lane;
                const int p00 = source_index(x0[x], y0, width, height, degrees);
                const int p10 = source_index(x1[x], y0, width, height, degrees);
                const int p01 = source_index(x0[x], y1, width, height, degrees);
                const int p11 = source_index(x1[x], y1, width, height, degrees);
                for (int c = 0; c < 3; ++c) {
                    q00[c][lane] = encoded(planes[c], p00, gain, lut, lut_max);
                    q10[c][lane] = encoded(planes[c], p10, gain, lut, lut_max);
                    q01[c][lane] = encoded(planes[c], p01, gain, lut, lut_max);
                    q11[c][lane] = encoded(planes[c], p11, gain, lut, lut_max);
                }
            }
            const float32x4_t fxv = vld1q_f32(fx.data() + dx);
            float32x4x3_t rgb;
            for (int c = 0; c < 3; ++c) {
                const float32x4_t a = vld1q_f32(q00[c]);
                const float32x4_t top = vfmaq_f32(a, vsubq_f32(vld1q_f32(q10[c]), a), fxv);
                const float32x4_t d = vld1q_f32(q01[c]);
                const float32x4_t bottom = vfmaq_f32(d, vsubq_f32(vld1q_f32(q11[c]), d), fxv);
                rgb.val[c] = vmulq_f32(vfmaq_f32(top, vsubq_f32(bottom, top), fyv), inv255);
            }
            float* out = dst + ((dy + pad_y) * image_size + dx + pad_x) * 3;
            vst3q_f32(out, rgb);
        }
        for (; dx < nw; ++dx) {
            const int p00 = source_index(x0[dx], y0, width, height, degrees);
            const int p10 = source_index(x1[dx], y0, width, height, degrees);
            const int p01 = source_index(x0[dx], y1, width, height, degrees);
            const int p11 = source_index(x1[dx], y1, width, height, degrees);
            float* out = dst + ((dy + pad_y) * image_size + dx + pad_x) * 3;
            for (int c = 0; c < 3; ++c) {
                const float top = encoded(planes[c], p00, gain, lut, lut_max) * (1 - fx[dx])
                                + encoded(planes[c], p10, gain, lut, lut_max) * fx[dx];
                const float bottom = encoded(planes[c], p01, gain, lut, lut_max) * (1 - fx[dx])
                                   + encoded(planes[c], p11, gain, lut, lut_max) * fx[dx];
                out[c] = (top * (1 - fy_scalar) + bottom * fy_scalar) / 255.0f;
            }
        }
    }

    env->ReleasePrimitiveArrayCritical(lut_array, lut, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(b_array, b, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(g_array, g, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(r_array, r, JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_activeperception_NativeTensorPreprocessor_fillFusedBayerSrgbRowStripe(
        JNIEnv* env, jobject, jobjectArray frame_arrays, jint n_sum,
        jint bayer_width, jint bayer_height, jstring cfa_string,
        jint orientation, jdoubleArray gains_array, jintArray bases_array,
        jintArray lut_array, jobject destination, jint image_size,
        jint output_y_start, jint output_y_end, jint resample_mode) {
    // resample_mode 0: area average in linear values, one clip after the gain (current).
    // resample_mode 1: the pre-2026-10-15 path, kept for the before/after comparison:
    // bilinear over four quads, each clipped and sRGB-encoded before blending.
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(destination));
    if (!dst || !frame_arrays || !gains_array || !bases_array || !lut_array ||
        !cfa_string || n_sum <= 0) return JNI_FALSE;
    const int available = env->GetArrayLength(frame_arrays);
    const int used = std::min(static_cast<int>(n_sum), available);
    const int gain_count = env->GetArrayLength(gains_array);
    const int base_count = env->GetArrayLength(bases_array);
    const int lut_length = env->GetArrayLength(lut_array);
    if (used <= 0 || gain_count <= 0 || base_count != gain_count || lut_length <= 0) {
        return JNI_FALSE;
    }

    std::vector<jintArray> frame_refs(used);
    std::vector<jint*> frames(used, nullptr);
    bool ok = true;
    // Resolve every JNI object/length before the first critical pin. CheckJNI correctly
    // rejects GetObjectArrayElement/GetArrayLength while a primitive array is pinned.
    for (int k = 0; k < used; ++k) {
        frame_refs[k] = static_cast<jintArray>(env->GetObjectArrayElement(frame_arrays, k));
        if (!frame_refs[k] || env->GetArrayLength(frame_refs[k]) < bayer_width * bayer_height) {
            ok = false; break;
        }
    }
    const char* cfa = ok ? env->GetStringUTFChars(cfa_string, nullptr) : nullptr;
    if (!cfa) ok = false;
    for (int k = 0; ok && k < used; ++k) {
        frames[k] = static_cast<jint*>(env->GetPrimitiveArrayCritical(frame_refs[k], nullptr));
        if (!frames[k]) ok = false;
    }
    auto* gains = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(gains_array, nullptr));
    auto* bases = static_cast<jint*>(env->GetPrimitiveArrayCritical(bases_array, nullptr));
    auto* lut = static_cast<jint*>(env->GetPrimitiveArrayCritical(lut_array, nullptr));
    if (!gains || !bases || !lut || !cfa) ok = false;

    if (ok) {
        int r_site = -1, b_site = -1, g_sites[2] = {-1, -1}, gn = 0;
        for (int i = 0; i < 4; ++i) {
            if (cfa[i] == 'R') r_site = i;
            else if (cfa[i] == 'B') b_site = i;
            else if (gn < 2) g_sites[gn++] = i;
        }
        ok = r_site >= 0 && b_site >= 0 && gn == 2;
        if (ok) {
            const int width = bayer_width / 2;
            const int height = bayer_height / 2;
            const int degrees = ((orientation % 360) + 360) % 360;
            const int oriented_w = (degrees == 90 || degrees == 270) ? height : width;
            const int oriented_h = (degrees == 90 || degrees == 270) ? width : height;
            const float scale = std::min(static_cast<float>(image_size) / oriented_w,
                                         static_cast<float>(image_size) / oriented_h);
            const int nw = std::lround(oriented_w * scale);
            const int nh = std::lround(oriented_h * scale);
            const int pad_x = (image_size - nw) / 2;
            const int pad_y = (image_size - nh) / 2;
            const int lut_max = lut_length - 1;
            // Area resampling: output pixel d covers the oriented quads [lo(d), lo(d+1)).
            // Every quad (and so every decoded site) contributes to exactly one output
            // pixel, all in linear values; the gain and the single clip come afterwards.
            auto span_lo = [](int d, int src, int dst) -> int {
                return static_cast<int>((static_cast<long long>(d) * src) / dst);
            };
            std::vector<int> xs(nw), xe(nw);
            for (int dx = 0; dx < nw; ++dx) {
                xs[dx] = std::min(span_lo(dx, oriented_w, nw), oriented_w - 1);
                xe[dx] = std::max(xs[dx] + 1, std::min(span_lo(dx + 1, oriented_w, nw), oriented_w));
            }
            // Sum of the burst frames at one quad: R, G1+G2 (kept as a sum so no integer
            // halving happens before averaging), B.
            auto quad = [&](int oriented_x, int oriented_y, long long out_rgb[3]) {
                const int p = source_index(oriented_x, oriented_y, width, height, degrees);
                const int bx = p % width;
                const int by = p / width;
                const int base = (by * 2) * bayer_width + bx * 2;
                const int offsets[4] = {0, 1, bayer_width, bayer_width + 1};
                long long sites[4] = {0, 0, 0, 0};
                for (int k = 0; k < used; ++k) {
                    const jint* frame = frames[k];
                    sites[0] += frame[base + offsets[0]];
                    sites[1] += frame[base + offsets[1]];
                    sites[2] += frame[base + offsets[2]];
                    sites[3] += frame[base + offsets[3]];
                }
                out_rgb[0] = sites[r_site];
                out_rgb[1] = sites[g_sites[0]] + sites[g_sites[1]];
                out_rgb[2] = sites[b_site];
            };
            const int begin = std::max(0, static_cast<int>(output_y_start));
            const int end = std::min(nh, static_cast<int>(output_y_end));
            if (resample_mode == 1) {
                std::vector<int> x0(nw), x1(nw);
                std::vector<float> fx(nw);
                for (int dx = 0; dx < nw; ++dx) {
                    const double sx = std::clamp((dx + 0.5) * oriented_w / nw - 0.5,
                                                 0.0, static_cast<double>(oriented_w - 1));
                    x0[dx] = static_cast<int>(std::floor(sx));
                    x1[dx] = std::min(x0[dx] + 1, oriented_w - 1);
                    fx[dx] = static_cast<float>(sx - x0[dx]);
                }
                auto demosaic = [&](int oriented_x, int oriented_y, long long out_rgb[3]) {
                    quad(oriented_x, oriented_y, out_rgb);
                    out_rgb[1] /= 2;      // the old path halved G in integers
                };
                for (int dy = begin; dy < end; ++dy) {
                    const double sy = std::clamp((dy + 0.5) * oriented_h / nh - 0.5,
                                                 0.0, static_cast<double>(oriented_h - 1));
                    const int y0 = static_cast<int>(std::floor(sy));
                    const int y1 = std::min(y0 + 1, oriented_h - 1);
                    const float fy = static_cast<float>(sy - y0);
                    for (int dx = 0; dx < nw; ++dx) {
                        long long q00[3], q10[3], q01[3], q11[3];
                        demosaic(x0[dx], y0, q00); demosaic(x1[dx], y0, q10);
                        demosaic(x0[dx], y1, q01); demosaic(x1[dx], y1, q11);
                        for (int lane = 0; lane < gain_count; ++lane) {
                            float* out = dst + bases[lane] +
                                ((dy + pad_y) * image_size + dx + pad_x) * 3;
                            const double gain = gains[lane];
                            for (int c = 0; c < 3; ++c) {
                                auto enc = [&](long long value) -> float {
                                    int index = static_cast<int>(value * gain);
                                    index = std::max(0, std::min(index, lut_max));
                                    return static_cast<float>(lut[index]);
                                };
                                const float top = enc(q00[c]) * (1.0f - fx[dx]) +
                                                  enc(q10[c]) * fx[dx];
                                const float bottom = enc(q01[c]) * (1.0f - fx[dx]) +
                                                     enc(q11[c]) * fx[dx];
                                out[c] = (top * (1.0f - fy) + bottom * fy) / 255.0f;
                            }
                        }
                    }
                }
            } else
            for (int dy = begin; dy < end; ++dy) {
                const int ys = std::min(span_lo(dy, oriented_h, nh), oriented_h - 1);
                const int ye = std::max(ys + 1, std::min(span_lo(dy + 1, oriented_h, nh), oriented_h));
                for (int dx = 0; dx < nw; ++dx) {
                    long long acc[3] = {0, 0, 0};
                    long long q[3];
                    for (int oy = ys; oy < ye; ++oy) {
                        for (int ox = xs[dx]; ox < xe[dx]; ++ox) {
                            quad(ox, oy, q);
                            acc[0] += q[0]; acc[1] += q[1]; acc[2] += q[2];
                        }
                    }
                    const double n = static_cast<double>((ye - ys) * (xe[dx] - xs[dx]));
                    const double linear[3] = { acc[0] / n, acc[1] / (2.0 * n), acc[2] / n };
                    for (int lane = 0; lane < gain_count; ++lane) {
                        float* out = dst + bases[lane] +
                            ((dy + pad_y) * image_size + dx + pad_x) * 3;
                        const double gain = gains[lane];
                        for (int c = 0; c < 3; ++c) {
                            const double v = linear[c] * gain;
                            int index = v <= 0.0 ? 0 : static_cast<int>(v);
                            index = std::min(index, lut_max);
                            out[c] = static_cast<float>(lut[index]) / 255.0f;
                        }
                    }
                }
            }
        }
    }

    if (lut) env->ReleasePrimitiveArrayCritical(lut_array, lut, JNI_ABORT);
    if (bases) env->ReleasePrimitiveArrayCritical(bases_array, bases, JNI_ABORT);
    if (gains) env->ReleasePrimitiveArrayCritical(gains_array, gains, JNI_ABORT);
    for (int k = 0; k < used; ++k) {
        if (frames[k]) env->ReleasePrimitiveArrayCritical(frame_refs[k], frames[k], JNI_ABORT);
    }
    if (cfa) env->ReleaseStringUTFChars(cfa_string, cfa);
    for (int k = 0; k < used; ++k) {
        if (frame_refs[k]) env->DeleteLocalRef(frame_refs[k]);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_activeperception_NativeTensorPreprocessor_decodeSubsampledBayer(
        JNIEnv* env, jobject, jobject source, jint source_byte_offset,
        jint source_width, jint source_height, jint row_stride, jint pixel_stride,
        jintArray black_array, jintArray destination_array, jint mode) {
    // mode 0: mean of the four same-colour pixels, signed (current).
    // mode 1: the pre-2026-10-15 decode, kept for the before/after comparison: one pixel
    // of the four, clipped at black.
    // mode 2: 6x6 blocks, mean of the nine same-colour pixels, signed (the glass).
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    if (!base || !black_array || !destination_array) return JNI_FALSE;
    auto* black = static_cast<jint*>(env->GetPrimitiveArrayCritical(black_array, nullptr));
    auto* destination = static_cast<jint*>(
            env->GetPrimitiveArrayCritical(destination_array, nullptr));
    if (!black || !destination) {
        if (black) env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
        if (destination) env->ReleasePrimitiveArrayCritical(
                destination_array, destination, JNI_ABORT);
        return JNI_FALSE;
    }

    base += source_byte_offset;
    const int output_width = mode == 2 ? (source_width / 6) * 2 : (source_width / 2) & ~1;
    const int output_height = mode == 2 ? (source_height / 6) * 2 : (source_height / 2) & ~1;
    if (env->GetArrayLength(destination_array) < output_width * output_height) {
        env->ReleasePrimitiveArrayCritical(destination_array, destination, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
        return JNI_FALSE;
    }

    if (mode == 2) {
        // Every 6x6 sensor block becomes one 2x2 Bayer quad: each site is the mean of the
        // block's nine same-colour pixels (rows y0, y0+2, y0+4; columns x0, x0+2, x0+4),
        // signed, not clipped, floor rounding as the Kotlin reference.
        auto px = [&](const uint8_t* row, int x) -> int {
            const uint8_t* p = row + static_cast<size_t>(x) * pixel_stride;
            return static_cast<int>(p[0]) | (static_cast<int>(p[1]) << 8);
        };
        for (int oy = 0; oy < output_height; ++oy) {
            const int y0 = (oy >> 1) * 6 + (oy & 1);
            const uint8_t* r0 = base + static_cast<size_t>(y0) * row_stride;
            const uint8_t* r1 = r0 + 2 * static_cast<size_t>(row_stride);
            const uint8_t* r2 = r1 + 2 * static_cast<size_t>(row_stride);
            jint* out = destination + static_cast<size_t>(oy) * output_width;
            const int channel_row = (y0 & 1) * 2;
            for (int ox = 0; ox < output_width; ++ox) {
                const int x0 = (ox >> 1) * 6 + (ox & 1);
                const int sum = px(r0, x0) + px(r0, x0 + 2) + px(r0, x0 + 4) +
                                px(r1, x0) + px(r1, x0 + 2) + px(r1, x0 + 4) +
                                px(r2, x0) + px(r2, x0 + 2) + px(r2, x0 + 4);
                out[ox] = floor_div9(sum - 9 * black[channel_row + (x0 & 1)] + 4);
            }
        }
        env->ReleasePrimitiveArrayCritical(destination_array, destination, 0);
        env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
        return JNI_TRUE;
    }

    if (mode == 1) {
        for (int oy = 0; oy < output_height; ++oy) {
            const int iy = (oy & ~1) * 2 + (oy & 1);
            const uint8_t* row_bytes = base + static_cast<size_t>(iy) * row_stride;
            jint* out = destination + static_cast<size_t>(oy) * output_width;
            const int channel_row = (iy & 1) * 2;
            for (int ox = 0; ox < output_width; ++ox) {
                const int ix = (ox & ~1) * 2 + (ox & 1);
                const uint8_t* p = row_bytes + static_cast<size_t>(ix) * pixel_stride;
                const int raw = static_cast<int>(p[0]) | (static_cast<int>(p[1]) << 8);
                out[ox] = std::max(0, raw - black[channel_row + (ix & 1)]);
            }
        }
        env->ReleasePrimitiveArrayCritical(destination_array, destination, 0);
        env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
        return JNI_TRUE;
    }

    // Every 4x4 sensor block becomes one 2x2 Bayer quad of the output. Each output site is
    // the MEAN of the four same-colour pixels of its block (rows iy, iy+2; columns ix, ix+2),
    // so every sensor pixel contributes. Values stay signed: the black level is removed but
    // nothing is clipped here; formation clips once, after summing, averaging and gain.
    for (int oy = 0; oy < output_height; ++oy) {
        const int iy = (oy & ~1) * 2 + (oy & 1);
        const uint8_t* row_a = base + static_cast<size_t>(iy) * row_stride;
        const uint8_t* row_b = base + static_cast<size_t>(iy + 2) * row_stride;
        jint* out = destination + static_cast<size_t>(oy) * output_width;
        const int channel_row = (iy & 1) * 2;
        int ox = 0;

        // Little-endian uint16 pixels. vld4 deinterleaves 32 source pixels: val[0]/val[2]
        // are columns 0,2 (+4k) of the even CFA column, val[1]/val[3] columns 1,3 (+4k).
        if (pixel_stride == 2) {
            const auto* ra = reinterpret_cast<const uint16_t*>(row_a);
            const auto* rb = reinterpret_cast<const uint16_t*>(row_b);
            const int32x4_t b0 = vdupq_n_s32(4 * black[channel_row] - 2);
            const int32x4_t b1 = vdupq_n_s32(4 * black[channel_row + 1] - 2);
            for (; ox + 16 <= output_width; ox += 16) {
                const int ix = (ox / 2) * 4;
                const uint16x8x4_t pa = vld4q_u16(ra + ix);
                const uint16x8x4_t pb = vld4q_u16(rb + ix);
                const uint32x4_t even_lo = vaddq_u32(
                        vaddl_u16(vget_low_u16(pa.val[0]), vget_low_u16(pa.val[2])),
                        vaddl_u16(vget_low_u16(pb.val[0]), vget_low_u16(pb.val[2])));
                const uint32x4_t even_hi = vaddq_u32(
                        vaddl_u16(vget_high_u16(pa.val[0]), vget_high_u16(pa.val[2])),
                        vaddl_u16(vget_high_u16(pb.val[0]), vget_high_u16(pb.val[2])));
                const uint32x4_t odd_lo = vaddq_u32(
                        vaddl_u16(vget_low_u16(pa.val[1]), vget_low_u16(pa.val[3])),
                        vaddl_u16(vget_low_u16(pb.val[1]), vget_low_u16(pb.val[3])));
                const uint32x4_t odd_hi = vaddq_u32(
                        vaddl_u16(vget_high_u16(pa.val[1]), vget_high_u16(pa.val[3])),
                        vaddl_u16(vget_high_u16(pb.val[1]), vget_high_u16(pb.val[3])));
                // (sum - 4*black + 2) >> 2, arithmetic shift = floor, same as the Kotlin
                // reference's Math.floorDiv.
                int32x4x2_t low = {
                        vshrq_n_s32(vsubq_s32(vreinterpretq_s32_u32(even_lo), b0), 2),
                        vshrq_n_s32(vsubq_s32(vreinterpretq_s32_u32(odd_lo), b1), 2) };
                int32x4x2_t high = {
                        vshrq_n_s32(vsubq_s32(vreinterpretq_s32_u32(even_hi), b0), 2),
                        vshrq_n_s32(vsubq_s32(vreinterpretq_s32_u32(odd_hi), b1), 2) };
                vst2q_s32(out + ox, low);
                vst2q_s32(out + ox + 8, high);
            }
        }
        for (; ox < output_width; ++ox) {
            const int ix = (ox & ~1) * 2 + (ox & 1);
            auto px = [&](const uint8_t* row, int x) -> int {
                const uint8_t* p = row + static_cast<size_t>(x) * pixel_stride;
                return static_cast<int>(p[0]) | (static_cast<int>(p[1]) << 8);
            };
            const int sum = px(row_a, ix) + px(row_a, ix + 2) + px(row_b, ix) + px(row_b, ix + 2);
            out[ox] = floor_div4(sum - 4 * black[channel_row + (ix & 1)] + 2);
        }
    }

    env->ReleasePrimitiveArrayCritical(destination_array, destination, 0);
    env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_activeperception_NativeTensorPreprocessor_decodeSubsampledRaw10(
        JNIEnv* env, jobject, jobject source, jint source_byte_offset,
        jint source_width, jint source_height, jint row_stride,
        jintArray black_array, jintArray destination_array, jint mode) {
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    if (!base || !black_array || !destination_array || (source_width & 3) != 0) {
        return JNI_FALSE;
    }
    auto* black = static_cast<jint*>(env->GetPrimitiveArrayCritical(black_array, nullptr));
    auto* destination = static_cast<jint*>(
            env->GetPrimitiveArrayCritical(destination_array, nullptr));
    if (!black || !destination) {
        if (black) env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
        if (destination) env->ReleasePrimitiveArrayCritical(
                destination_array, destination, JNI_ABORT);
        return JNI_FALSE;
    }

    base += source_byte_offset;
    const int output_width = mode == 2 ? (source_width / 6) * 2 : (source_width / 2) & ~1;
    const int output_height = mode == 2 ? (source_height / 6) * 2 : (source_height / 2) & ~1;
    if (env->GetArrayLength(destination_array) < output_width * output_height) {
        env->ReleasePrimitiveArrayCritical(destination_array, destination, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
        return JNI_FALSE;
    }

    if (mode == 2) {
        // 6x6 blocks as in decodeSubsampledBayer's mode 2; a pixel is unpacked from its group.
        auto px = [&](const uint8_t* row, int x) -> int {
            const uint8_t* q = row + static_cast<size_t>(x >> 2) * 5;
            const int k = x & 3;
            return (static_cast<int>(q[k]) << 2) | ((q[4] >> (2 * k)) & 0x3);
        };
        for (int oy = 0; oy < output_height; ++oy) {
            const int y0 = (oy >> 1) * 6 + (oy & 1);
            const uint8_t* r0 = base + static_cast<size_t>(y0) * row_stride;
            const uint8_t* r1 = r0 + 2 * static_cast<size_t>(row_stride);
            const uint8_t* r2 = r1 + 2 * static_cast<size_t>(row_stride);
            jint* out = destination + static_cast<size_t>(oy) * output_width;
            const int channel_row = (y0 & 1) * 2;
            for (int ox = 0; ox < output_width; ++ox) {
                const int x0 = (ox >> 1) * 6 + (ox & 1);
                const int sum = px(r0, x0) + px(r0, x0 + 2) + px(r0, x0 + 4) +
                                px(r1, x0) + px(r1, x0 + 2) + px(r1, x0 + 4) +
                                px(r2, x0) + px(r2, x0 + 2) + px(r2, x0 + 4);
                out[ox] = floor_div9(sum - 9 * black[channel_row + (x0 & 1)] + 4);
            }
        }
        env->ReleasePrimitiveArrayCritical(destination_array, destination, 0);
        env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
        return JNI_TRUE;
    }

    if (mode == 1) {           // pre-2026-10-15: pixels 0 and 1 of each group, clipped
        for (int oy = 0; oy < output_height; ++oy) {
            const int iy = (oy & ~1) * 2 + (oy & 1);
            const uint8_t* row = base + static_cast<size_t>(iy) * row_stride;
            jint* out = destination + static_cast<size_t>(oy) * output_width;
            const int channel_row = (iy & 1) * 2;
            for (int group = 0; group < output_width / 2; ++group) {
                const uint8_t* packed = row + static_cast<size_t>(group) * 5;
                const int tails = packed[4];
                const int p0 = (static_cast<int>(packed[0]) << 2) | (tails & 0x3);
                const int p1 = (static_cast<int>(packed[1]) << 2) | ((tails >> 2) & 0x3);
                out[group * 2] = std::max(0, p0 - black[channel_row]);
                out[group * 2 + 1] = std::max(0, p1 - black[channel_row + 1]);
            }
        }
        env->ReleasePrimitiveArrayCritical(destination_array, destination, 0);
        env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
        return JNI_TRUE;
    }

    // Android RAW10 stores four 10-bit pixels in five bytes. Bytes 0..3 contain each
    // pixel's upper eight bits and byte 4 contains the four 2-bit tails. One packed group
    // spans exactly the four columns of a 4x4 block: pixels 0,2 are the even CFA column and
    // 1,3 the odd one. Each output site is the mean of the four same-colour pixels of its
    // block (rows iy, iy+2), signed, not clipped. No unpacked full-size buffer is allocated.
    for (int oy = 0; oy < output_height; ++oy) {
        const int iy = (oy & ~1) * 2 + (oy & 1);
        const uint8_t* row_a = base + static_cast<size_t>(iy) * row_stride;
        const uint8_t* row_b = base + static_cast<size_t>(iy + 2) * row_stride;
        jint* out = destination + static_cast<size_t>(oy) * output_width;
        const int channel_row = (iy & 1) * 2;
        const int groups = output_width / 2;
        for (int group = 0; group < groups; ++group) {
            const uint8_t* qa = row_a + static_cast<size_t>(group) * 5;
            const uint8_t* qb = row_b + static_cast<size_t>(group) * 5;
            auto unpack = [](const uint8_t* q, int k) -> int {
                return (static_cast<int>(q[k]) << 2) | ((q[4] >> (2 * k)) & 0x3);
            };
            const int even = unpack(qa, 0) + unpack(qa, 2) + unpack(qb, 0) + unpack(qb, 2);
            const int odd = unpack(qa, 1) + unpack(qa, 3) + unpack(qb, 1) + unpack(qb, 3);
            out[group * 2] = floor_div4(even - 4 * black[channel_row] + 2);
            out[group * 2 + 1] = floor_div4(odd - 4 * black[channel_row + 1] + 2);
        }
    }

    env->ReleasePrimitiveArrayCritical(destination_array, destination, 0);
    env->ReleasePrimitiveArrayCritical(black_array, black, JNI_ABORT);
    return JNI_TRUE;
}

// Packs one HWC float lane of the detector input (values in [0, 1]) into opaque ARGB pixels
// for the preview and the saved photo. Each channel is v * 255 truncated and clamped to
// [0, 255] (NaN and negatives give 0), exactly what the Kotlin loop it replaces computed;
// that loop cost ~45 ms per frame on the measurement thread.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_activeperception_NativeTensorPreprocessor_tensorLaneToArgb(
        JNIEnv* env, jobject, jobject source, jint float_offset, jintArray destination_array) {
    const auto* src = static_cast<const float*>(env->GetDirectBufferAddress(source));
    const jlong capacity_bytes = env->GetDirectBufferCapacity(source);
    if (!src || capacity_bytes < 0 || float_offset < 0) return JNI_FALSE;
    const jsize count = env->GetArrayLength(destination_array);
    if ((static_cast<jlong>(float_offset) + 3LL * count) * 4LL > capacity_bytes) return JNI_FALSE;
    auto* out = static_cast<jint*>(env->GetPrimitiveArrayCritical(destination_array, nullptr));
    if (!out) return JNI_FALSE;
    auto level = [](float v) -> uint32_t {
        const float s = v * 255.0f;
        if (!(s > 0.0f)) return 0u;
        if (s >= 255.0f) return 255u;
        return static_cast<uint32_t>(static_cast<int>(s));
    };
    const float* px = src + float_offset;
    for (jsize i = 0; i < count; ++i, px += 3) {
        out[i] = static_cast<jint>(0xFF000000u | (level(px[0]) << 16) | (level(px[1]) << 8) |
                                   level(px[2]));
    }
    env->ReleasePrimitiveArrayCritical(destination_array, out, 0);
    return JNI_TRUE;
}
