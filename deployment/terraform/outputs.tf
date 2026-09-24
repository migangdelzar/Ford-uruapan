output "emme_1_instance_id" {
  value = try(oci_core_instance.emme["emme-1"].id, null)
}

output "emme_2_instance_id" {
  value = try(oci_core_instance.emme["emme-2"].id, null)
}

output "data_volume_id" {
  value = try(oci_core_volume.data[0].id, null)
}

output "backup_bucket_name" {
  value = try(oci_objectstorage_bucket.backups[0].name, null)
}
