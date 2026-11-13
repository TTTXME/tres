package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P656 (owner's logs of 11-11 / 11-12, Mali-G925: the merge's GPU prewarm worker died in the driver's compiler at every install and
 * again at later process starts, 21 tombstones, nothing learned): a prewarm that dies twice on a build without a lesson is not run
 * again on that build; another colour block, another build or the developer switch still run it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamPrewarmGiveUpTest {
    private Context context;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("scam_gpu_learned", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void twoDeathsOfOneBlockGiveItsPrewarmUp() {
        assertFalse(ScamHybridBurst.prewarmGivenUp(context, 1));
        assertFalse("one death: run again (a driver hiccup)", ScamHybridBurst.notePrewarmDeath(context, 1));
        assertFalse(ScamHybridBurst.prewarmGivenUp(context, 1));
        assertTrue(ScamHybridBurst.notePrewarmDeath(context, 1));
        assertTrue(ScamHybridBurst.prewarmGivenUp(context, 1));
        assertFalse("another colour block keeps its prewarm", ScamHybridBurst.prewarmGivenUp(context, 2));
    }

    @Test
    public void anotherBuildTriesAgain() {
        ScamHybridBurst.notePrewarmDeath(context, 4);
        ScamHybridBurst.notePrewarmDeath(context, 4);
        assertTrue(ScamHybridBurst.prewarmGivenUp(context, 4));
        // a system or app update: the build key differs, the counts start again
        context.getSharedPreferences("scam_gpu_learned", Context.MODE_PRIVATE).edit().putString("prewarm_died_build", "older|1").commit();
        assertFalse(ScamHybridBurst.prewarmGivenUp(context, 4));
        assertFalse(ScamHybridBurst.notePrewarmDeath(context, 4));
        assertFalse(ScamHybridBurst.prewarmGivenUp(context, 4));
    }

    @Test
    public void thePrewarmChecksAndCountsOnlyWhatTaughtNothing() throws Exception {
        final String src = new String(Files.readAllBytes(new File(
                "src/main/java/com/scamera/app/processing/opengl/postpipeline/ScamNeuralClient.java").toPath()), StandardCharsets.UTF_8);
        final int check = src.indexOf("ScamHybridBurst.prewarmGivenUp(app,b)");
        final int spawn = src.indexOf("WorkerSpawn.start(app,args");
        assertTrue("checked before the worker is spawned", check > 0 && spawn > check);
        assertTrue(src.contains("scamDevSwitch(\"prewarm_give_up\",true)"));
        final int learned = src.indexOf("if(ScamHybridBurst.learnFromPrewarm(app,report.toString()))");
        final int counted = src.indexOf("else if(giveUp&&ScamHybridBurst.notePrewarmDeath(app,b))");
        assertTrue("a death is counted only when nothing was learned", learned > 0 && counted > learned);
    }
}
