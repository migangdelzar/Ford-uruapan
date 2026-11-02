package com.ford.service.conversations.application.graph;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedList;

/** Fixes the 1.9.1 saver read path, which otherwise leaves its autocommit connection open. */
public final class FordPostgresSaverV2 extends PostgresSaverV2 {

    private FordPostgresSaverV2(Builder builder) throws Exception {
        super(builder);
    }

    public static FordPostgresSaverV2 create(DataSource dataSource) throws Exception {
        var builder = PostgresSaverV2.builder()
                .datasource(dataSource)
                .stateSerializer(new ObjectStreamStateSerializer<>(ConversationGraphState::new))
                .createTables(false);
        return new FordPostgresSaverV2(builder);
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) throws Exception {
        var checkpoints = new LinkedList<Checkpoint>();
        var threadId = threadId(config);

        try (Connection connection = datasource.getConnection();
             var checkThread = connection.prepareStatement(sqlCommands.get("sqlCheckThread"))) {
            checkThread.setString(1, threadId);
            try (var result = checkThread.executeQuery()) {
                result.next();
                var count = result.getInt(1);
                if (count == 0) {
                    return checkpoints;
                }
                if (count > 1) {
                    throw new IllegalStateException(
                            "More than one open checkpoint thread exists for the requested thread ID");
                }
            }

            try (var query = connection.prepareStatement(sqlCommands.get("sqlSelectCheckpoints"))) {
                query.setString(1, threadId);
                try (var result = query.executeQuery()) {
                    while (result.next()) {
                        checkpoints.add(Checkpoint.builder()
                                .id(result.getString("checkpoint_id"))
                                .nodeId(result.getString("node_id"))
                                .nextNodeId(result.getString("next_node_id"))
                                .state(decodeState(result.getBytes("base64_data"),
                                        result.getString("state_content_type")))
                                .build());
                    }
                }
            }
        }
        return checkpoints;
    }
}
