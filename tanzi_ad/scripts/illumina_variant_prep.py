#!/usr/bin/env python
"""Prepare Illumina called variants, merging into single sample GATK-compatible VCFs.

Usage:
  illumina_variant_prep.py <in config> <cores>
"""
import glob
import multiprocessing
import pprint
import os
import subprocess
import sys

import yaml
try:
    from concurrent import futures
except ImportError:
    import futures

def main(config_file, cores):
    with open(config_file) as in_handle:
        config = yaml.load(in_handle)
    idremap = read_remap_file(config["idmapping"])
    samples = list(get_input_samples(config["inputs"], idremap))
    problem = [x for x in samples if x["id"] is None]
    if len(problem) > 0:
        print "Problem identifiers"
        for p in problem:
            print p["illuminaid"], os.path.basename(p["dir"])
        raise NotImplementedError
    if cores > 1:
        pool = futures.ProcessPoolExecutor(cores)
        it = pool.map(run_illumina_prep, [(s, config) for s in samples if s["id"] is not None])
    else:
        it = map(run_illumina_prep, [(s, config) for s in samples if s["id"] is not None])
    for x in it:
        pass

def run_illumina_prep(args):
    sample, config = args
    tmp_dir = config.get("tmpdir", os.getcwd())
    if not os.path.exists(tmp_dir):
        try:
            os.makedirs(tmp_dir)
        except OSError:
            assert os.path.exists(tmp_dir)
    out_file = os.path.join(os.getcwd(), "%s.vcf" % sample["id"])
    if not os.path.exists(out_file):
        print sample["id"], sample["dir"], out_file
        subprocess.check_call(["java", "-Xms1g", "-Xmx2g", "-jar", config["bcbio.variation"],
                               "variant-utils", "illumina", sample["dir"],
                               sample["id"], config["ref"]["GRCh37"],
                               config["ref"]["hg19"],
                               "--outdir", os.getcwd(),
                               "--tmpdir", tmp_dir])
    return sample["id"]

def dir_to_sample(dname, idremap):
    vcf_file = os.path.join(dname, "Variations", "SNPs.vcf")
    with open(vcf_file) as in_handle:
        for line in in_handle:
            if line.startswith("#CHROM"):
                illumina_id = line.split("\t")[-1].replace("_POLY", "").rstrip()
                return {"id": idremap.get(illumina_id), "dir": dname,
                        "illuminaid": illumina_id}
    raise ValueError("Did not find sample information in %s" % vcf_file)

def get_input_samples(fpats, idremap):
    for fpat in fpats:
        for dname in glob.glob(fpat):
            if os.path.isdir(dname):
                yield dir_to_sample(dname, idremap)

def read_remap_file(in_file):
    out = {}
    with open(in_file) as in_handle:
        in_handle.next() # header
        for line in in_handle:
            patient_id, illumina_id = line.rstrip().split()
            out[illumina_id] = patient_id
    return out

if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]))
