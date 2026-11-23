#!/usr/bin/env tclsh8.6

proc pack_uue {fname} {
    exec /usr/bin/uuencode $fname $fname > $fname.uu
    set out [exec ls -la $fname $fname.uu]
    puts $out

    set out [exec cp -v $fname.uu /home/antek/dev/unrar5/libarchive/libarchive/test/$fname.uu]
    puts $out
}

if {$argc < 1} {
    puts "Enter the name of the RAR file in the argument."
    exit 1
}

foreach fname $argv {
    pack_uue $argv
}
