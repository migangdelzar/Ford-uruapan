package com.ford.service.ingestion.adapter.out.imports;

import com.ford.service.ingestion.application.port.out.ManualImportSource;
import com.ford.service.ingestion.domain.SourceSnapshot;
import com.ford.service.ingestion.domain.SyncRequest;
import com.ford.service.shared.FreshnessStatus;
import com.ford.service.shared.SourceCandidate;
import com.ford.service.shared.SourceKind;
import com.ford.service.shared.SourceMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

public final class CsvImportAdapter implements ManualImportSource {
    private final Path csvPath;

    public CsvImportAdapter(Path csvPath) {
        this.csvPath = csvPath;
    }

    @Override
    public SourceSnapshot fetchManual(SyncRequest request) {
        try {
            var lines = Files.readAllLines(csvPath);
            var runId = UUID.randomUUID();
            var candidates = new ArrayList<SourceCandidate>();
            for (var index = 1; index < lines.size(); index++) {
                var columns = lines.get(index).split(",", -1);
                if (columns.length < 2) {
                    continue;
                }
                var id = columns[0].trim();
                var payload = "{\"id\":\"" + id + "\",\"value\":\"" + columns[1].trim() + "\"}";
                candidates.add(new SourceCandidate(SourceKind.MANUAL_OVERRIDE, payload,
                        new SourceMetadata("manual-csv", id, request.requestedAt(), null, null,
                                FreshnessStatus.FRESH, payload, runId)));
            }
            return new SourceSnapshot(runId, candidates);
        } catch (IOException exception) {
            throw new IllegalStateException("Manual import could not be read", exception);
        }
    }
}
