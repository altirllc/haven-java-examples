package haven.plumb.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** The one activity — it echoes the token back, stamped with who ran it. */
@ActivityInterface
public interface PingActivities {

    @ActivityMethod
    String echo(String token);
}
