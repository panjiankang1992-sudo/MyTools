#!/usr/bin/env python3
"""Compile and run the isolated probe using already-built Maven test dependencies."""
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
repo = evidence.parents[2]
service = repo / "service" / "toolbox-service"
report = service / "target" / "surefire-reports" / "TEST-com.yuyutian.mytools.toolbox.AdminAuthApiTest.xml"
root = ET.parse(report).getroot()
classpath = next(prop.attrib["value"] for prop in root.findall("./properties/property")
                 if prop.attrib["name"] == "java.class.path")
with tempfile.TemporaryDirectory(prefix="toolbox-admin-auth-review-") as classes:
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", classes,
                    str(evidence / "AdminAuthReviewProbe.java")], cwd=service, check=True)
    subprocess.run(["java", "-cp", classes + os.pathsep + classpath,
                    "review.AdminAuthReviewProbe"], cwd=service, check=True)
