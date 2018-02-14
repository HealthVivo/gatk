package org.broadinstitute.hellbender.tools.walkers.realignmentfilter;

import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.mutable.MutableInt;
import org.broadinstitute.barclay.argparser.*;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.ReadsContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.engine.VariantWalker;
import org.broadinstitute.hellbender.utils.read.AlignmentUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.ReadUtils;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.broadinstitute.hellbender.utils.variant.GATKVCFHeaderLines;
import picard.cmdline.programgroups.VariantFilteringProgramGroup;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>Filter false positive alignment artifacts from a VCF callset.</p>
 *
 * <p>
 *     FilterAlignmentArtifacts identifies alignment artifacts, that is, apparent variants due to reads being mapped to the wrong genomic locus.
 * </p>
 * <p>
 *     Alignment artifacts can occur whenever there is sufficient sequence similarity between two or more regions in the genome
 *     to confuse the alignment algorithm.  This can occur when the aligner for whatever reason overestimate how uniquely a read
 *     maps, thereby assigning it too high of a mapping quality.  It can also occur through no fault of the aligner due to gaps in
 *     the reference, which can also hide the true position to which a read should map.  By using a good alignment algorithm
 *     (the GATK wrapper of BWA-MEM), giving it sensitive settings (which may have been impractically slow for the original
 *     bam alignment) and mapping to the best available reference we can avoid these pitfalls.  The last point is especially important:
 *     one can (and should) use a BWA-MEM index image corresponding to the best reference, regardless of the reference to which
 *     the bam was aligned.
 * </p>
 * <p>
 *     This tool is featured in the Somatic Short Mutation calling Best Practice Workflow.
 *     See <a href="https://software.broadinstitute.org/gatk/documentation/article?id=11136">Tutorial#11136</a> for a
 *     step-by-step description of the workflow and <a href="https://software.broadinstitute.org/gatk/documentation/article?id=11127">Article#11127</a>
 *     for an overview of what traditional somatic calling entails. For the latest pipeline scripts, see the
 *     <a href="https://github.com/broadinstitute/gatk/tree/master/scripts/mutect2_wdl">Mutect2 WDL scripts directory</a>.
 * </p>
 * <p>
 *     The bam input to this tool should be the reassembly bamout produced by HaplotypeCaller or Mutect2 in the process of generating
 *     the input callset.  The original bam will also work but might fail to filter some indels.  The reference passed with the -R argument
 *     but be the reference to which the input bam was realigned.  This does not need to correspond to the reference of the BWAS-MEM
 *     index image.  The latter should be derived from the best available reference, for example hg38 in humans as of February 2018.
 * </p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * gatk FilterAlignmentArtifacts \
 *   -R hg19.fasta
 *   -V somatic.vcf.gz \
 *   -I somatic_bamout.bam \
 *   --bwa-mem-index-image hg38.index_image \
 *   -O filtered.vcf.gz
 * </pre>
 *
 */
@CommandLineProgramProperties(
        summary = "Filter alignment artifacts from a vcf callset.",
        oneLineSummary = "Filter alignment artifacts from a vcf callset.",
        programGroup = VariantFilteringProgramGroup.class
)
@DocumentedFeature
@ExperimentalFeature
public class FilterAlignmentArtifacts extends VariantWalker {
    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName=StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            doc="The output filtered VCF file", optional=false)
    private final String outputVcf = null;

    public static final int DEFAULT_INDEL_START_TOLERANCE = 5;
    public static final String INDEL_START_TOLERANCE_LONG_NAME = "indel-start-tolerance";
    @Argument(fullName = INDEL_START_TOLERANCE_LONG_NAME,
            doc="Max distance between indel start of aligned read in the bam and the variant in the vcf", optional=false)
    private final int indelStartTolerance = DEFAULT_INDEL_START_TOLERANCE;

    @ArgumentCollection
    protected RealignmentArgumentCollection realignmentArgumentCollection = new RealignmentArgumentCollection();

    private SAMFileHeader header;
    private VariantContextWriter vcfWriter;
    private Realigner realigner;

    @Override
    public boolean requiresReads() { return true; }

    @Override
    public boolean requiresReference() { return true; }

    @Override
    public void onTraversalStart() {
        header = getHeaderForReads();
        realigner = new Realigner(realignmentArgumentCollection, getHeaderForReads());
        vcfWriter = createVCFWriter(new File(outputVcf));

        final VCFHeader inputHeader = getHeaderForVariants();
        final Set<VCFHeaderLine> headerLines = inputHeader.getMetaDataInSortedOrder();
        headerLines.add(GATKVCFHeaderLines.getFilterLine(GATKVCFConstants.ALIGNMENT_ARTIFACT_FILTER_NAME));
        headerLines.addAll(getDefaultToolVCFHeaderLines());
        final VCFHeader vcfHeader = new VCFHeader(headerLines, inputHeader.getGenotypeSamples());
        vcfWriter.writeHeader(vcfHeader);

    }

    @Override
    public Object onTraversalSuccess() {
        return "SUCCESS";
    }

    @Override
    public void apply(final VariantContext vc, final ReadsContext readsContext, final ReferenceContext refContext, final FeatureContext fc) {
        if (vc.getNAlleles() == 1) {
            vcfWriter.add(vc);
            return;
        }

        final List<String> variantSamples = vc.getGenotypes().stream().filter(g -> !g.isHomRef()).map(g -> g.getSampleName()).collect(Collectors.toList());

        final MutableInt failedRealignmentCount = new MutableInt(0);
        final MutableInt succeededRealignmentCount = new MutableInt(0);

        for (final GATKRead read : readsContext) {
            if (!variantSamples.contains(ReadUtils.getSampleName(read, header))) {
                continue;
            }

            if (supportsVariant(read, vc)) {

            }
        }

        final VariantContextBuilder vcb = new VariantContextBuilder(vc);
        vcfWriter.add(vcb.make());
    }

    @Override
    public void closeTool() {
        if ( vcfWriter != null ) {
            vcfWriter.close();
        }
    }

    private boolean supportsVariant(final GATKRead read, final VariantContext vc) {
        final byte[] readBases = read.getBasesNoCopy();

        final int variantPositionInRead = ReadUtils.getReadCoordinateForReferenceCoordinate(read.getSoftStart(), read.getCigar(),
                vc.getStart(), ReadUtils.ClippingTail.RIGHT_TAIL, true);
        if ( variantPositionInRead == ReadUtils.CLIPPING_GOAL_NOT_REACHED || AlignmentUtils.isInsideDeletion(read.getCigar(), variantPositionInRead)) {
            return false;
        }

        for (final Allele allele : vc.getAlternateAlleles()) {
            final int referenceLength = vc.getReference().length();
            if (allele.length() == referenceLength) {   // SNP or MNP -- check whether read bases match variant allele bases
                if (allele.basesMatch(ArrayUtils.subarray(readBases, variantPositionInRead, Math.min(variantPositionInRead + allele.length(), readBases.length)))) {
                    return true;
                }
            } else {    // indel -- check if the read has the right CIGAR operator near position -- we don't require exact an exact match because indel representation is non-unique
                final boolean isDeletion = allele.length() < referenceLength;
                int readPosition = 0;    // offset by 1 because the variant position is one base before the first indel base
                for (final CigarElement cigarElement : read.getCigarElements()) {
                    if (Math.abs(readPosition - variantPositionInRead) <= indelStartTolerance) {
                        if (isDeletion && mightSupportDeletion(cigarElement) || !isDeletion && mightSupportInsertion(cigarElement)) {
                            return true;
                        }
                    } else {
                        readPosition += cigarElement.getLength();
                    }
                }
            }
        }
        return false;
    }

    private static boolean mightSupportDeletion(final CigarElement cigarElement) {
        final CigarOperator cigarOperator = cigarElement.getOperator();
        return cigarOperator == CigarOperator.D || cigarOperator == CigarOperator.S;
    }

    private final static boolean mightSupportInsertion(final CigarElement cigarElement) {
        final CigarOperator cigarOperator = cigarElement.getOperator();
        return cigarOperator == CigarOperator.I || cigarOperator == CigarOperator.S;
    }
}