//! The one owned allocation that crosses the boundary.

use std::ptr;

/// A Rust allocation, described in a way C can hold.
///
/// The caller must zero this before its first use. Every value this library produces is
/// released through [`opairdrop_buffer_free`], which also zeroes the struct, so a
/// zero-initialized struct can be filled and released any number of times.
#[repr(C)]
#[derive(Debug)]
pub struct OpairdropBuffer {
    /// First byte of the allocation, or null when there is none.
    pub data: *mut u8,
    /// Bytes of content, excluding any slack in the allocation.
    pub len: usize,
    /// Bytes actually allocated. Needed to reconstruct the [`Vec`] this came from.
    pub capacity: usize,
}

impl OpairdropBuffer {
    /// An empty buffer, which is what a caller must start from.
    pub const EMPTY: OpairdropBuffer = OpairdropBuffer {
        data: ptr::null_mut(),
        len: 0,
        capacity: 0,
    };

    /// The contents, which are only valid while nothing has released the buffer.
    ///
    /// # Safety
    ///
    /// `self.data` must either be null or point at `self.len` initialized bytes from an
    /// allocation that has not been released.
    pub unsafe fn as_slice(&self) -> &[u8] {
        if self.data.is_null() || self.len == 0 {
            &[]
        } else {
            // SAFETY: the caller guarantees the pointer and length, which is exactly the
            // contract of `from_raw_parts`.
            unsafe { std::slice::from_raw_parts(self.data, self.len) }
        }
    }
}

impl Default for OpairdropBuffer {
    fn default() -> OpairdropBuffer {
        OpairdropBuffer::EMPTY
    }
}

/// Releases a buffer this library filled, and resets it to empty.
///
/// Passing a null pointer, or a struct that is already empty, does nothing: the reset is
/// what makes a double release harmless rather than a double free.
///
/// # Safety
///
/// `buffer` must be null, or point at a struct whose `data`/`len`/`capacity` came from
/// this library and has not been released yet.
#[no_mangle]
pub unsafe extern "C" fn opairdrop_buffer_free(buffer: *mut OpairdropBuffer) {
    if buffer.is_null() {
        return;
    }
    // SAFETY: the caller guarantees the pointer is valid for writes.
    let previous = unsafe { ptr::replace(buffer, OpairdropBuffer::EMPTY) };
    if previous.data.is_null() {
        return;
    }
    // SAFETY: `data`/`len`/`capacity` were taken from a `Vec` with the same layout and no
    // one else has released it, which is the caller's side of the contract.
    drop(unsafe { Vec::from_raw_parts(previous.data, previous.len, previous.capacity) });
}

/// Moves `bytes` into `buffer`, releasing whatever was there before.
///
/// # Safety
///
/// `buffer` must be null or valid for writes, and must satisfy the contract of
/// [`opairdrop_buffer_free`].
pub(crate) unsafe fn fill(buffer: *mut OpairdropBuffer, bytes: Vec<u8>) {
    if buffer.is_null() {
        return;
    }
    let mut bytes = bytes;
    // Without this the allocation can be far larger than the content, and the caller has
    // no way to know how much of it was ever written.
    bytes.shrink_to_fit();
    let len = bytes.len();
    let capacity = bytes.capacity();
    let data = bytes.as_mut_ptr();
    std::mem::forget(bytes);

    // SAFETY: `buffer` is writable and its previous contents came from this library.
    unsafe {
        opairdrop_buffer_free(buffer);
        ptr::write(
            buffer,
            OpairdropBuffer {
                data,
                len,
                capacity,
            },
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_filled_buffer_round_trips_its_bytes() {
        let mut buffer = OpairdropBuffer::EMPTY;
        // SAFETY: `buffer` is a live local.
        unsafe { fill(&mut buffer, b"names".to_vec()) };
        // SAFETY: nothing has released the buffer.
        assert_eq!(unsafe { buffer.as_slice() }, b"names");
        // SAFETY: the buffer came from `fill`.
        unsafe { opairdrop_buffer_free(&mut buffer) };
        assert!(buffer.data.is_null());
        assert_eq!(buffer.len, 0);
    }

    #[test]
    fn releasing_an_empty_buffer_does_nothing() {
        let mut buffer = OpairdropBuffer::EMPTY;
        // SAFETY: an empty buffer is a valid argument.
        unsafe { opairdrop_buffer_free(&mut buffer) };
        // SAFETY: a null pointer is a valid argument.
        unsafe { opairdrop_buffer_free(ptr::null_mut()) };
    }

    #[test]
    fn releasing_twice_is_not_a_double_free() {
        let mut buffer = OpairdropBuffer::EMPTY;
        // SAFETY: `buffer` is a live local.
        unsafe { fill(&mut buffer, b"twice".to_vec()) };
        // SAFETY: the buffer came from `fill`; the second release sees the reset struct.
        unsafe {
            opairdrop_buffer_free(&mut buffer);
            opairdrop_buffer_free(&mut buffer);
        }
        assert!(buffer.data.is_null());
    }

    #[test]
    fn filling_twice_releases_the_first_allocation() {
        let mut buffer = OpairdropBuffer::EMPTY;
        // SAFETY: `buffer` is a live local and each fill releases the previous content.
        unsafe {
            fill(&mut buffer, b"first".to_vec());
            fill(&mut buffer, b"second".to_vec());
        }
        // SAFETY: nothing has released the current content.
        assert_eq!(unsafe { buffer.as_slice() }, b"second");
        // SAFETY: the buffer came from `fill`.
        unsafe { opairdrop_buffer_free(&mut buffer) };
    }

    #[test]
    fn the_capacity_is_trimmed_so_the_caller_sees_only_what_was_written() {
        let mut roomy = Vec::with_capacity(4096);
        roomy.extend_from_slice(b"short");
        let mut buffer = OpairdropBuffer::EMPTY;
        // SAFETY: `buffer` is a live local.
        unsafe { fill(&mut buffer, roomy) };
        assert_eq!(buffer.len, 5);
        assert_eq!(buffer.capacity, 5);
        // SAFETY: the buffer came from `fill`.
        unsafe { opairdrop_buffer_free(&mut buffer) };
    }

    #[test]
    fn a_null_out_pointer_is_ignored_rather_than_dereferenced() {
        // SAFETY: a null pointer is the documented way to say "no buffer".
        unsafe { fill(ptr::null_mut(), b"dropped".to_vec()) };
    }
}
