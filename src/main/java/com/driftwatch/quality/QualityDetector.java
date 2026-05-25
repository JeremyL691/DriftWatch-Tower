package com.driftwatch.quality;

import java.util.List;

public interface QualityDetector {
    List<DraftAlert> detect(DetectionContext context);
}
