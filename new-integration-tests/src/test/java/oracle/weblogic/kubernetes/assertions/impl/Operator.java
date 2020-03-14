package oracle.weblogic.kubernetes.assertions.impl;

import java.util.Random;
import java.util.concurrent.Callable;

import static oracle.weblogic.kubernetes.extensions.LoggedTest.logger;

public class Operator {

    public static Callable<Boolean> isRunning() {
        // this uses a rand, to simulate that this operation can take
        // variable amounts of time to complete
        return () -> {
            int outcome = new Random(System.currentTimeMillis()).nextInt(3);
            if (outcome == 1) {
                logger.info("Operator pod is in 'Running' state");
                return true;
            } else {
                logger.info("Operator is not in 'Running' state yet, current state is 'ImagePull'");
                return false;
            }
        };
    }
}
