# Health Services 1.1.0-alpha05 supplies its own consumer rule preserving fields
# of generated protobuf messages for reflective deserialization. Do not keep the
# entire proto package: it also includes the shaded protobuf runtime and prevents
# R8 from shrinking, optimizing, and obfuscating otherwise unused code.
# Recheck the library's consumer rules when changing the Health Services version.
