locals {
  total_ocpus  = var.create_instances ? length(var.instances) : 0
  total_memory = var.create_instances ? length(var.instances) * 6 : 0
}

resource "oci_core_instance" "emme" {
  for_each = var.create_instances ? var.instances : {}

  availability_domain = var.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = each.value.display_name
  shape               = "VM.Standard.A1.Flex"

  shape_config {
    ocpus         = 1
    memory_in_gbs = 6
  }

  source_details {
    source_type             = "image"
    source_id               = each.value.image_ocid
    boot_volume_size_in_gbs = 50
  }

  create_vnic_details {
    subnet_id        = var.existing_subnet_id
    assign_public_ip = each.value.assign_public_ip
    hostname_label   = each.key
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
  }

  lifecycle {
    precondition {
      condition     = local.total_ocpus <= var.always_free_ocpus && local.total_memory <= var.always_free_memory_gb
      error_message = "Requested compute exceeds the configured Always Free 2 OCPU / 12 GB budget."
    }
  }
}
