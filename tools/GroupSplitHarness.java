import com.esjzone.reader.EpubFile;
import com.esjzone.reader.SplitPlan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Desktop harness for the "every N chapters" split.
 *
 * <p>Usage: GroupSplitHarness &lt;in.epub&gt; &lt;outDir&gt; &lt;perFile&gt;</p>
 */
public final class GroupSplitHarness {

    public static void main(String[] args) throws Exception {
        File source = new File(args[0]);
        File outDir = new File(args[1]);
        int perFile = Integer.parseInt(args[2]);
        outDir.mkdirs();

        EpubFile.Book book = EpubFile.read(source);
        System.out.println("source   : " + source.getName() + "  " + source.length() + " bytes");
        System.out.println("book     : " + book.title);
        System.out.println("chapters : " + book.chapters.size());

        ArrayList<EpubFile.Chapter> chosen = new ArrayList<EpubFile.Chapter>();
        ArrayList<Integer> positions = new ArrayList<Integer>();
        for (int i = 0; i < book.chapters.size(); i++) {
            chosen.add(book.chapters.get(i));
            positions.add(i);
        }

        String base = SplitPlan.sanitize(book.title);

        for (int mode = 0; mode <= 2; mode++) {
            ArrayList<SplitPlan.Job> jobs =
                    SplitPlan.plan(base, chosen, positions, mode, perFile);
            String label = mode == SplitPlan.EACH ? "EACH" : mode == SplitPlan.GROUP ? "GROUP" : "MERGE";
            int total = 0;
            for (int i = 0; i < jobs.size(); i++) {
                total += jobs.get(i).chapters.size();
            }
            System.out.println();
            System.out.println("--- " + label + " (perFile=" + perFile + ") -> "
                    + jobs.size() + " file(s), " + total + " chapters covered");
            int show = Math.min(jobs.size(), 8);
            for (int i = 0; i < show; i++) {
                SplitPlan.Job job = jobs.get(i);
                long bytes = 0;
                for (int c = 0; c < job.chapters.size(); c++) {
                    bytes += job.chapters.get(c).bytes;
                }
                System.out.println(String.format("    %-58s %2d 章  %8d B",
                        job.fileName, job.chapters.size(), bytes));
            }
            if (jobs.size() > show) {
                System.out.println("    … 共 " + jobs.size() + " 个");
            }

            if (mode == SplitPlan.GROUP) {
                int written = 0;
                for (int i = 0; i < jobs.size(); i++) {
                    SplitPlan.Job job = jobs.get(i);
                    File target = new File(outDir, "group-" + (i + 1) + ".epub");
                    OutputStream out = new FileOutputStream(target);
                    try {
                        EpubFile.export(source, book, job.chapters, job.title, out);
                    } finally {
                        out.close();
                    }
                    written++;
                    System.out.println(String.format("    wrote %-14s %8d bytes  (%d 章)",
                            target.getName(), target.length(), job.chapters.size()));
                }
                System.out.println("    exported " + written + " files to " + outDir);
            }
        }

        // clamping behaviour
        System.out.println();
        System.out.println("clamp: 0->" + SplitPlan.parseGroupSize("0")
                + "  1->" + SplitPlan.parseGroupSize("1")
                + "  abc->" + SplitPlan.parseGroupSize("abc")
                + "  9999->" + SplitPlan.parseGroupSize("9999")
                + "  7->" + SplitPlan.parseGroupSize("7"));
    }

    private GroupSplitHarness() {
    }
}
