package goodvin.locsync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

public class GpxTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // Client rows (only "fix" ones count) and server rows in one track; a pause over a minute and a new
    // file each start a segment; a cut-off row is skipped.
    @Test
    public void writesFixesAsSegments() throws Exception {
        File client = tmp.newFile("track-client-b1.csv");
        Files.write(client.toPath(), String.join("\n",
                "wall_ms,elapsed_ms,type,fix_ts,provider,sats,lat,lon,alt",
                "1,1,fix,1700000000000,fused,9,55.7500000,37.6000000,150.0",
                "2,2,out,,,,55.7500001,37.6000001,",
                "3,3,car,,,,,,",
                "4,4,fix,1700000001000,fused,9,55.7500100,37.6000100,",
                "5,5,fix,1700000100000,fused,9,55.7600000,37.6100000,151.0",
                "6,6,fix,17000").getBytes());
        File server = tmp.newFile("track-server.csv");
        Files.write(server.toPath(), String.join("\n",
                "wall_ms,elapsed_ms,source,fix_time_ms,fix_elapsed_ms,lat,lon,alt",
                "1,1,gps,1700000200000,1,55.7700000,37.6200000,152.0").getBytes());
        File out = tmp.newFile("t.gpx");
        assertEquals(4, Gpx.write(new File[]{client, new File(tmp.getRoot(), "missing.csv"), server}, out));
        String gpx = new String(Files.readAllBytes(out.toPath()));
        assertEquals(3, gpx.split("<trkseg>", -1).length - 1);
        assertEquals(3, gpx.split("</trkseg>", -1).length - 1);
        assertTrue(gpx.contains("<trkpt lat=\"55.7500000\" lon=\"37.6000000\"><ele>150.0</ele><time>2023-11-14T22:13:20Z</time></trkpt>"));
        assertTrue(gpx.contains("<trkpt lat=\"55.7500100\" lon=\"37.6000100\"><time>"));
        assertTrue(gpx.trim().endsWith("</gpx>"));
    }
}
