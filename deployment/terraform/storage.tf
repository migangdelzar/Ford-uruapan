resource "oci_core_volume" "data" {
  count               = var.manage_data_volume ? 1 : 0
  availability_domain = var.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = "emme-2-data"
  size_in_gbs         = var.data_volume_size_gb
  vpus_per_gb         = 10

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_core_volume_attachment" "data" {
  count           = var.manage_data_volume && var.create_instances ? 1 : 0
  attachment_type = "paravirtualized"
  instance_id     = oci_core_instance.emme["emme-2"].id
  volume_id       = oci_core_volume.data[0].id
}

resource "oci_objectstorage_bucket" "backups" {
  count          = var.manage_backup_bucket ? 1 : 0
  compartment_id = var.compartment_ocid
  name           = var.backup_bucket_name
  namespace      = var.object_storage_namespace
  storage_tier   = "Standard"

  lifecycle {
    prevent_destroy = true
  }
}
