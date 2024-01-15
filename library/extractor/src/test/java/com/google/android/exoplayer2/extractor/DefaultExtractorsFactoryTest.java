/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.extractor;

import static com.google.android.exoplayer2.util.Assertions.checkState;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.extractor.amr.AmrExtractor;
import com.google.android.exoplayer2.extractor.avi.AviExtractor;
import com.google.android.exoplayer2.extractor.bmp.BmpExtractor;
import com.google.android.exoplayer2.extractor.flac.FlacExtractor;
import com.google.android.exoplayer2.extractor.flv.FlvExtractor;
import com.google.android.exoplayer2.extractor.heif.HeifExtractor;
import com.google.android.exoplayer2.extractor.jpeg.JpegExtractor;
import com.google.android.exoplayer2.extractor.mkv.MatroskaExtractor;
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor;
import com.google.android.exoplayer2.extractor.mp4.FragmentedMp4Extractor;
import com.google.android.exoplayer2.extractor.mp4.Mp4Extractor;
import com.google.android.exoplayer2.extractor.ogg.OggExtractor;
import com.google.android.exoplayer2.extractor.png.PngExtractor;
import com.google.android.exoplayer2.extractor.ts.Ac3Extractor;
import com.google.android.exoplayer2.extractor.ts.Ac4Extractor;
import com.google.android.exoplayer2.extractor.ts.AdtsExtractor;
import com.google.android.exoplayer2.extractor.ts.PsExtractor;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.extractor.wav.WavExtractor;
import com.google.android.exoplayer2.extractor.webp.WebpExtractor;
import com.google.android.exoplayer2.text.SubtitleTranscodingExtractor;
import com.google.android.exoplayer2.util.MimeTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link DefaultExtractorsFactory}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultExtractorsFactoryTest {

  @Test
  public void createExtractors_withoutMediaInfo_optimizesSniffingOrder() {
    DefaultExtractorsFactory defaultExtractorsFactory = new DefaultExtractorsFactory();

    Extractor[] extractors = defaultExtractorsFactory.createExtractors();

    List<Class<? extends Extractor>> extractorClasses = getUnderlyingExtractorClasses(extractors);
    assertThat(extractorClasses.subList(0, 3))
        .containsExactly(FlvExtractor.class, FlacExtractor.class, WavExtractor.class)
        .inOrder();
    assertThat(extractorClasses.subList(3, 5))
        .containsExactly(Mp4Extractor.class, FragmentedMp4Extractor.class);
    assertThat(extractorClasses.subList(5, extractors.length))
        .containsExactly(
            AmrExtractor.class,
            PsExtractor.class,
            OggExtractor.class,
            TsExtractor.class,
            MatroskaExtractor.class,
            AdtsExtractor.class,
            Ac3Extractor.class,
            Ac4Extractor.class,
            Mp3Extractor.class,
            AviExtractor.class,
            JpegExtractor.class,
            PngExtractor.class,
            WebpExtractor.class,
            BmpExtractor.class,
            HeifExtractor.class)
        .inOrder();
  }

  @Test
  public void createExtractors_withMediaInfo_startsWithExtractorsMatchingHeadersAndThenUri() {
    DefaultExtractorsFactory defaultExtractorsFactory = new DefaultExtractorsFactory();
    Uri uri = Uri.parse("test-cbr-info-header.mp3");
    Map<String, List<String>> responseHeaders = new HashMap<>();
    responseHeaders.put("Content-Type", Collections.singletonList(MimeTypes.VIDEO_MP4));

    Extractor[] extractors = defaultExtractorsFactory.createExtractors(uri, responseHeaders);

    List<Class<? extends Extractor>> extractorClasses = getUnderlyingExtractorClasses(extractors);
    assertThat(extractorClasses.subList(0, 2))
        .containsExactly(Mp4Extractor.class, FragmentedMp4Extractor.class);
    assertThat(extractorClasses.get(2)).isEqualTo(Mp3Extractor.class);
  }

  @Test
  public void createExtractors_withMediaInfo_optimizesSniffingOrder() {
    DefaultExtractorsFactory defaultExtractorsFactory = new DefaultExtractorsFactory();
    Uri uri = Uri.parse("test-cbr-info-header.mp3");
    Map<String, List<String>> responseHeaders = new HashMap<>();
    responseHeaders.put("Content-Type", Collections.singletonList(MimeTypes.VIDEO_MP4));

    Extractor[] extractors = defaultExtractorsFactory.createExtractors(uri, responseHeaders);

    List<Class<? extends Extractor>> extractorClasses = getUnderlyingExtractorClasses(extractors);
    assertThat(extractorClasses.subList(3, extractors.length))
        .containsExactly(
            FlvExtractor.class,
            FlacExtractor.class,
            WavExtractor.class,
            AmrExtractor.class,
            PsExtractor.class,
            OggExtractor.class,
            TsExtractor.class,
            MatroskaExtractor.class,
            AdtsExtractor.class,
            Ac3Extractor.class,
            Ac4Extractor.class,
            AviExtractor.class,
            JpegExtractor.class,
            PngExtractor.class,
            WebpExtractor.class,
            BmpExtractor.class,
            HeifExtractor.class)
        .inOrder();
  }

  @Test
  public void subtitleTranscoding_notEnabledByDefault() {
    DefaultExtractorsFactory defaultExtractorsFactory = new DefaultExtractorsFactory();

    Extractor[] extractors = defaultExtractorsFactory.createExtractors();

    for (Extractor extractor : extractors) {
      assertThat(extractor.getClass()).isNotEqualTo(SubtitleTranscodingExtractor.class);
    }
  }

  @Test
  public void subtitleTranscoding_ifEnabled_wrapsExtractorsThatSupportMuxedSubtitles() {
    DefaultExtractorsFactory defaultExtractorsFactory =
        new DefaultExtractorsFactory().setTextTrackTranscodingEnabled(true);

    Extractor[] extractors = defaultExtractorsFactory.createExtractors();

    Extractor aviExtractor = null;
    Extractor matroskaExtractor = null;
    Extractor mp4Extractor = null;
    Extractor fragmentedMp4Extractor = null;
    Extractor tsExtractor = null;

    for (Extractor extractor : extractors) {
      if (extractor.getUnderlyingImplementation() instanceof AviExtractor) {
        checkState(aviExtractor == null);
        aviExtractor = extractor;
      } else if (extractor.getUnderlyingImplementation() instanceof MatroskaExtractor) {
        checkState(matroskaExtractor == null);
        matroskaExtractor = extractor;
      } else if (extractor.getUnderlyingImplementation() instanceof Mp4Extractor) {
        checkState(mp4Extractor == null);
        mp4Extractor = extractor;
      } else if (extractor.getUnderlyingImplementation() instanceof FragmentedMp4Extractor) {
        checkState(fragmentedMp4Extractor == null);
        fragmentedMp4Extractor = extractor;
      } else if (extractor.getUnderlyingImplementation() instanceof TsExtractor) {
        checkState(tsExtractor == null);
        tsExtractor = extractor;
      }
    }
    assertThat(aviExtractor).isNotInstanceOf(SubtitleTranscodingExtractor.class);
    assertThat(matroskaExtractor).isInstanceOf(SubtitleTranscodingExtractor.class);
    assertThat(mp4Extractor).isNotInstanceOf(SubtitleTranscodingExtractor.class);
    assertThat(fragmentedMp4Extractor).isNotInstanceOf(SubtitleTranscodingExtractor.class);
    assertThat(tsExtractor).isNotInstanceOf(SubtitleTranscodingExtractor.class);
  }

  private static List<Class<? extends Extractor>> getUnderlyingExtractorClasses(
      Extractor[] extractors) {
    List<Class<? extends Extractor>> extractorClasses = new ArrayList<>();
    for (Extractor extractor : extractors) {
      extractorClasses.add(extractor.getUnderlyingImplementation().getClass());
    }
    return extractorClasses;
  }
}
