package eu.startales.spacepixels.mpc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** PSV to ADES XML, schema validation, and the MPC test service's form. */
public class AdesValidationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static String psv() {
        long time = Instant.parse("2022-08-24T05:13:22.149Z").toEpochMilli();
        var context = new AdesPsv.Context("W84", "A. Tester", List.of("A. Observer", "B. Observer"), List.of("A. Tester"),
                "Reflector", 4.0, "CCD", "CCD", "Archival data");
        var observation = new MpcObservation(1, time, 120_000, 10, 20, 357.207083, -1.805139, 0.123, 0.13, "Gaia3", "K");
        return AdesPsv.write(context, List.of(new AdesPsv.Tracklet(null, null, AdesPsv.trkSub(time, 3), List.of(observation, observation))));
    }

    /** The context's elements and children, one optical element per row with its non-empty columns in order. */
    @Test
    public void turnsPsvIntoXml() {
        String xml = AdesValidation.toXml(psv());
        assertTrue(xml, xml.contains("<ades version=\"2022\"><obsBlock><obsContext><observatory><mpcCode>W84</mpcCode></observatory>"
                + "<submitter><name>A. Tester</name></submitter><observers><name>A. Observer</name><name>B. Observer</name></observers>"));
        assertTrue(xml, xml.contains("<telescope><design>Reflector</design><aperture>4.0</aperture><detector>CCD</detector></telescope>"));
        assertTrue(xml, xml.contains("<optical><trkSub>S082403</trkSub><mode>CCD</mode><stn>W84</stn><obsTime>2022-08-24T05:13:22.149Z</obsTime>"
                + "<ra>357.2070830</ra><dec>-1.8051390</dec><rmsRA>0.123</rmsRA><rmsDec>0.130</rmsDec><astCat>Gaia3</astCat><exp>120</exp>"
                + "<notes>K</notes></optical>"));
        assertFalse("empty columns are left out", xml.contains("<permID>"));
        assertEquals(2, xml.split("<optical>", -1).length - 1);
        assertTrue(AdesValidation.toXml("# version=2022\n# comment\n! line a < b & c\n").contains("<line>a &lt; b &amp; c</line>"));
    }

    /** A schema's complaints come back as text; a file it accepts gives none. */
    @Test
    public void validatesAgainstTheSchema() throws Exception {
        String schema = """
                <?xml version="1.0"?>
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                  <xsd:element name="ades"><xsd:complexType><xsd:sequence><xsd:element name="obsBlock"><xsd:complexType><xsd:sequence>
                    <xsd:element name="obsContext"><xsd:complexType><xsd:sequence><xsd:any processContents="skip" minOccurs="0" maxOccurs="unbounded"/></xsd:sequence></xsd:complexType></xsd:element>
                    <xsd:element name="obsData"><xsd:complexType><xsd:sequence><xsd:element name="optical" maxOccurs="unbounded"><xsd:complexType><xsd:sequence>
                      <xsd:any processContents="skip" minOccurs="0" maxOccurs="unbounded"/></xsd:sequence></xsd:complexType></xsd:element></xsd:sequence></xsd:complexType></xsd:element>
                  </xsd:sequence></xsd:complexType></xsd:element></xsd:sequence>
                  <xsd:attribute name="version"><xsd:simpleType><xsd:restriction base="xsd:string"><xsd:enumeration value="2022"/></xsd:restriction></xsd:simpleType></xsd:attribute>
                  </xsd:complexType></xsd:element>
                </xsd:schema>
                """;
        Files.writeString(folder.getRoot().toPath().resolve("submit.xsd"), schema, StandardCharsets.UTF_8);
        assertEquals(List.of(), AdesValidation.validate(psv(), folder.getRoot().toPath()));
        var problems = AdesValidation.validate(psv().replace("# version=2022", "# version=2017"), folder.getRoot().toPath());
        assertFalse(problems.isEmpty());
        assertTrue(problems.toString(), problems.getFirst().contains("2017"));
    }

    /** The test service's form: acknowledgement, e-mail, optional object type, and the file as an ordinary field. */
    @Test
    public void buildsTheTestServiceForm() {
        var fields = MpcTestSubmission.fields("# version=2022\n", "SpacePixels test", "someone@example.org", null);
        assertEquals(List.of("ack", "ac2", "source"), List.copyOf(fields.keySet()));
        assertEquals(List.of("ack", "ac2", "obj_type", "source"),
                List.copyOf(MpcTestSubmission.fields("x", "a", "someone@example.org", "NEO").keySet()));
        String body = new String(MpcTestSubmission.body(fields, "B"), StandardCharsets.UTF_8);
        assertTrue(body, body.startsWith("--B\r\nContent-Disposition: form-data; name=\"ack\"\r\n\r\nSpacePixels test\r\n"));
        assertTrue(body, body.contains("name=\"source\"\r\n\r\n# version=2022\n\r\n--B--\r\n"));
        assertThrows(IllegalArgumentException.class, () -> MpcTestSubmission.fields("x", "a", "not an address", null));
    }
}
