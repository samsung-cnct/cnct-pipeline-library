#!/usr/bin/env python

"""
parse.py - replace simple yaml values
Usage: 
  parse.py [-h] --file filename.yaml [--dry-run] --key-val a.b.c=val

Options:
    -h, --help            show this help message and exit
    --file filename.yaml  replace in this YAML file
    --dry-run             Don't replace in file, just print to stdout
    --key-val a.b.c=val   Key/Value pair to replace in yaml

This script allows simple yaml values to be replaced by dot-path. For example, give a yaml file /my-home/file.yaml like:

# my awesome yaml config
some:
    value:
        is: something

calling 'parse.yaml --file /my-home/file.yaml --key-val some.value.is=other' Will result in /my-home/file.yaml like:

# my awesome yaml config
some:
    value:
        is: other

Note the preserved comment line.

It is necessary because upstream helm chart repository (https://github.com/helm/charts) requires chart values.yaml 
files to be annotated with comments, and normal java/groovy YAML parsers will not preserve comment lines when setting 
version information for charts (such as docker tags and Chart.yaml version strings)  
"""

__author__ = "Marat Garafutdinov"
__copyright__ = "Copyright 2018, Samsung CNCT"
__credits__ = ["Marat Garafutdinov"]
__license__ = "Apache"
__version__ = "0.0.1"
__maintainer__ = "Marat Garafutdinov"
__email__ = "marat.g@samsung.com"
__status__ = "Development"

import sys
from ruamel.yaml import YAML
from functools import reduce
from argparse import ArgumentParser

def main():
    # handle arguments
    parser = ArgumentParser()
    parser.add_argument("--file", dest="filename", metavar="filename.yaml", required=True,
        help="replace in this YAML file")
    parser.add_argument("--dry-run", action='store_true',
        dest="dryrun", help="Don't replace in file, just print to stdout")
    parser.add_argument("--key-val", dest="kv", metavar="a.b.c=val", required=True,
        help="Key/Value pair to replace in yaml")
    args = parser.parse_args()

    yaml = YAML()
    yaml.explicit_start = False
    yaml.indent(mapping=3)
    yaml.preserve_quotes = True

    try:
        with open(args.filename) as fp:
            data = yaml.load(fp)
    except IOError:
        print("File '" + args.filename + "' not found!")
        exit(1)

    # get key and value
    key = args.kv.split('=')[0]
    val = args.kv.split('=')[1]

    # get first part of the key dot-path
    lookupKey = key.split('.')
    lookupKey.pop()

    # get last part of key dot-path
    itemKey = key.split('.')[-1]

    # reduce down to last lookupKey dictionary value and assign it to val 
    try:
        setItem = reduce(lambda c, k: c[k], lookupKey, data) 
    except KeyError:
        print("'" + key + "' is not a valid dot-path in " + args.filename)
        exit(1)

    # check if this path exists in yaml
    if setItem.get(itemKey, None) == None:
        print("'" + key + "' is not a valid dot-path in " + args.filename)
        exit(1)

    setItem[itemKey] = val

    ofp = None
    if args.dryrun:
        ofp = sys.stdout
    else:
        try:
            ofp = open(args.filename, 'w')
        except IOError:
            print("File '" + args.filename + "' not found!")
            exit(1)

    yaml.dump(data, ofp)

if __name__== "__main__":
    main()


    
