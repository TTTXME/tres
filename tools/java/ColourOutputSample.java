import com.scamera.app.processing.color.IccEmbed;
import com.scamera.app.processing.color.IccProfiles;
import com.scamera.app.processing.color.OutputColour;
import com.scamera.app.processing.heif.HeifColourPatch;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Host side of tools/check_colour_output.py (P46): the app's own colour code on files written by other encoders.
 *
 *   java ColourOutputSample icc OUT.icc                    the Display P3 profile
 *   java ColourOutputSample jpeg IN.jpg OUT.jpg            the profile into a JPEG (IccEmbed.jpegInserting)
 *   java ColourOutputSample webp IN.webp OUT.webp W H      the profile into a WebP (IccEmbed.addToWebp)
 *   java ColourOutputSample heif IN.heic OUT.heic          the profile into a HEIF (HeifColourPatch, nclx -> P3)
 *   java ColourOutputSample matrices                       the matrices and luma weights as JSON
 */
public final class ColourOutputSample {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "icc":
                Files.write(Paths.get(args[1]), IccProfiles.displayP3());
                break;
            case "jpeg": {
                final byte[] in = Files.readAllBytes(Paths.get(args[1]));
                try (OutputStream out = Files.newOutputStream(Paths.get(args[2]))) {
                    final OutputStream tagged = IccEmbed.jpegInserting(out, IccProfiles.displayP3());
                    // in odd pieces, as an encoder writes
                    for (int i = 0; i < in.length; i += 777) tagged.write(in, i, Math.min(777, in.length - i));
                    tagged.close();
                }
                break;
            }
            case "webp": {
                final Path out = Paths.get(args[2]);
                Files.copy(Paths.get(args[1]), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                IccEmbed.addToWebp(out, IccProfiles.displayP3(), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                break;
            }
            case "heif": {
                final Path out = Paths.get(args[2]);
                Files.copy(Paths.get(args[1]), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!HeifColourPatch.patch(out, IccProfiles.displayP3(), OutputColour.PRIMARIES_P3)) throw new IllegalStateException("not patched");
                break;
            }
            case "matrices":
                System.out.println("{\"srgbToP3\":" + Arrays.toString(OutputColour.srgbToP3()) + ",\"p3ToSrgb\":" + Arrays.toString(OutputColour.p3ToSrgb())
                        + ",\"srgbToBt2020\":" + Arrays.toString(OutputColour.srgbToBt2020()) + ",\"p3ToBt2020\":" + Arrays.toString(OutputColour.p3ToBt2020())
                        + ",\"lumaP3\":" + Arrays.toString(OutputColour.lumaP3()) + "}");
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }
}
